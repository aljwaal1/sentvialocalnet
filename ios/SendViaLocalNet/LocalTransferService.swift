import Foundation
import Network
import UIKit
import Darwin

struct LocalDevice: Identifiable, Hashable {
    let id: String
    var name: String
    var type: String
    var ip: String
    var port: Int
    var selected: Bool
    var lastSeen: Date
}

struct PendingFile: Identifiable, Hashable {
    let id = UUID()
    let url: URL
    let name: String
    let size: Int64
}

struct ReceivedFile: Identifiable, Hashable {
    let id = UUID()
    let url: URL
    let name: String
    let size: Int64
    let receivedAt: Date
}

final class LocalTransferService: ObservableObject {
    @Published var devices: [LocalDevice] = []
    @Published var pendingFiles: [PendingFile] = []
    @Published var receivedFiles: [ReceivedFile] = []
    @Published var status = "جاهز"
    @Published var localIP = "0.0.0.0"
    @Published var receiverRunning = false
    @Published var sending = false
    @Published var progress: Double = 0

    private let transferPort: UInt16 = 5051
    private let discoveryPort: UInt16 = 5052
    private let networkQueue = DispatchQueue(label: "svln.network", qos: .userInitiated)
    private let discoveryQueue = DispatchQueue(label: "svln.discovery", qos: .utility)
    private var listener: NWListener?
    private var discoveryFD: Int32 = -1
    private var discoveryRunning = false
    private var started = false

    private lazy var deviceId: String = {
        if let saved = UserDefaults.standard.string(forKey: "svln.device.id"), !saved.isEmpty { return saved }
        let value = UUID().uuidString.lowercased()
        UserDefaults.standard.set(value, forKey: "svln.device.id")
        return value
    }()

    var deviceName: String {
        let raw = UIDevice.current.name.replacingOccurrences(of: "|", with: " ")
        return String(raw.prefix(80))
    }

    func start() {
        guard !started else { return }
        started = true
        localIP = Self.bestLocalIPv4() ?? "0.0.0.0"
        startReceiver()
        startDiscoveryResponder()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.discover() }
    }

    deinit {
        listener?.cancel()
        discoveryRunning = false
        if discoveryFD >= 0 { Darwin.close(discoveryFD) }
    }

    func toggleDevice(_ id: String) {
        guard let index = devices.firstIndex(where: { $0.id == id }) else { return }
        devices[index].selected.toggle()
    }

    func clearPendingFiles() {
        for item in pendingFiles where item.url.path.hasPrefix(FileManager.default.temporaryDirectory.path) {
            try? FileManager.default.removeItem(at: item.url)
        }
        pendingFiles.removeAll()
        progress = 0
    }

    func prepareFiles(_ urls: [URL]) {
        clearPendingFiles()
        var prepared: [PendingFile] = []
        for source in urls {
            let scoped = source.startAccessingSecurityScopedResource()
            defer { if scoped { source.stopAccessingSecurityScopedResource() } }
            do {
                let name = Self.safeFileName(source.lastPathComponent)
                let target = Self.uniqueURL(in: FileManager.default.temporaryDirectory, name: name)
                try FileManager.default.copyItem(at: source, to: target)
                let values = try target.resourceValues(forKeys: [.fileSizeKey])
                prepared.append(PendingFile(url: target, name: name, size: Int64(values.fileSize ?? 0)))
            } catch {
                status = "تعذر تجهيز ملف: \(source.lastPathComponent)"
            }
        }
        pendingFiles = prepared
        if !prepared.isEmpty { status = "تم اختيار \(prepared.count) ملف" }
    }

    func discover() {
        guard discoveryFD >= 0 else {
            status = "جاري تشغيل اكتشاف الأجهزة…"
            startDiscoveryResponder()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.discover() }
            return
        }
        localIP = Self.bestLocalIPv4() ?? localIP
        status = "جاري البحث عن الأجهزة…"
        let token = String(Int(Date().timeIntervalSince1970 * 1000), radix: 16)
        let message = "SVLN_DISCOVER|\(token)"
        let targets = ["255.255.255.255", Self.subnetBroadcast(for: localIP)].compactMap { $0 }
        discoveryQueue.async { [weak self] in
            guard let self = self else { return }
            for target in Set(targets) {
                for _ in 0..<3 {
                    self.sendUDP(message, host: target, port: self.discoveryPort)
                    usleep(80_000)
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.4) { [weak self] in
                guard let self = self else { return }
                self.devices.sort { $0.lastSeen > $1.lastSeen }
                self.status = self.devices.isEmpty ? "لم يتم العثور على أجهزة. تأكد أنها على نفس Wi‑Fi." : "تم العثور على \(self.devices.count) جهاز"
            }
        }
    }

    func sendSelected() {
        let targets = devices.filter(\.selected)
        guard !targets.isEmpty else { status = "حدد جهازًا واحدًا على الأقل"; return }
        guard !pendingFiles.isEmpty else { status = "اختر ملفًا واحدًا على الأقل"; return }
        guard !sending else { return }
        sending = true
        progress = 0
        let files = pendingFiles
        Task { [weak self] in
            guard let self = self else { return }
            var completed = 0
            var succeeded = 0
            let total = max(1, targets.count * files.count)
            for device in targets {
                for file in files {
                    await MainActor.run { self.status = "إرسال \(file.name) إلى \(device.name)…" }
                    if await self.send(file: file, to: device) { succeeded += 1 }
                    completed += 1
                    await MainActor.run { self.progress = Double(completed) / Double(total) }
                }
            }
            await MainActor.run {
                self.sending = false
                self.status = "انتهى الإرسال: نجح \(succeeded) من \(total)"
            }
        }
    }

    private func send(file: PendingFile, to device: LocalDevice) async -> Bool {
        guard let url = URL(string: "http://\(device.ip):\(device.port)/upload") else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 180
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue(file.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? file.name, forHTTPHeaderField: "X-File-Name")
        request.setValue(String(file.size), forHTTPHeaderField: "X-File-Size")
        request.setValue(deviceId, forHTTPHeaderField: "X-SVLN-Sender-ID")
        request.setValue(deviceName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? deviceName, forHTTPHeaderField: "X-SVLN-Sender-Name")
        do {
            let (_, response) = try await URLSession.shared.upload(for: request, fromFile: file.url)
            return ((response as? HTTPURLResponse)?.statusCode ?? 500) < 300
        } catch {
            await MainActor.run { self.status = "فشل إرسال \(file.name): \(error.localizedDescription)" }
            return false
        }
    }

    private func startReceiver() {
        do {
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true
            guard let port = NWEndpoint.Port(rawValue: transferPort) else { return }
            let listener = try NWListener(using: parameters, on: port)
            listener.newConnectionHandler = { [weak self] connection in self?.handleHTTP(connection) }
            listener.stateUpdateHandler = { [weak self] state in
                DispatchQueue.main.async {
                    switch state {
                    case .ready:
                        self?.receiverRunning = true
                        self?.status = "الاستقبال يعمل على المنفذ 5051"
                    case .failed(let error):
                        self?.receiverRunning = false
                        self?.status = "تعذر تشغيل الاستقبال: \(error.localizedDescription)"
                    case .cancelled:
                        self?.receiverRunning = false
                    default: break
                    }
                }
            }
            listener.start(queue: networkQueue)
            self.listener = listener
        } catch {
            status = "تعذر تشغيل الاستقبال: \(error.localizedDescription)"
        }
    }

    private func handleHTTP(_ connection: NWConnection) {
        connection.start(queue: networkQueue)
        var headerBuffer = Data()
        var readHeader: (() -> Void)!
        readHeader = { [weak self, weak connection] in
            guard let self = self, let connection = connection else { return }
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, complete, error in
                if let data = data { headerBuffer.append(data) }
                let delimiter = Data("\r\n\r\n".utf8)
                if let range = headerBuffer.range(of: delimiter) {
                    let headerData = headerBuffer.subdata(in: 0..<range.lowerBound)
                    let bodyStart = headerBuffer.subdata(in: range.upperBound..<headerBuffer.count)
                    let headerText = String(data: headerData, encoding: .utf8) ?? ""
                    self.processRequest(headerText, initialBody: bodyStart, connection: connection)
                    return
                }
                if headerBuffer.count > 128 * 1024 || complete || error != nil {
                    self.sendHTTP(connection, code: 400, body: "Bad request")
                    return
                }
                readHeader()
            }
        }
        readHeader()
    }

    private func processRequest(_ headerText: String, initialBody: Data, connection: NWConnection) {
        let lines = headerText.components(separatedBy: "\r\n")
        guard let first = lines.first else { sendHTTP(connection, code: 400, body: "Bad request"); return }
        let firstParts = first.split(separator: " ")
        guard firstParts.count >= 2 else { sendHTTP(connection, code: 400, body: "Bad request"); return }
        let method = String(firstParts[0]).uppercased()
        let path = String(firstParts[1])
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[key] = value
        }
        if method == "OPTIONS" { sendHTTP(connection, code: 204, body: ""); return }
        guard path.hasPrefix("/upload") else { sendHTTP(connection, code: 404, body: "Not found"); return }
        if method == "GET" {
            let body = "{\"ok\":true,\"name\":\"\(Self.jsonEscape(deviceName))\",\"type\":\"ios\",\"port\":5051}"
            sendHTTP(connection, code: 200, body: body, contentType: "application/json")
            return
        }
        guard method == "POST" else { sendHTTP(connection, code: 405, body: "Method not allowed"); return }

        let encodedName = headers["x-file-name"] ?? "received-file"
        let decodedName = encodedName.removingPercentEncoding ?? encodedName
        let name = Self.safeFileName(decodedName)
        let expected = Int64(headers["x-file-size"] ?? headers["content-length"] ?? "") ?? -1
        let folder = Self.receiveFolder()
        let destination = Self.uniqueURL(in: folder, name: name)
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: destination) else { sendHTTP(connection, code: 500, body: "Cannot create file"); return }

        var written: Int64 = 0
        do {
            if !initialBody.isEmpty {
                try handle.write(contentsOf: initialBody)
                written += Int64(initialBody.count)
            }
        } catch {
            try? handle.close(); try? FileManager.default.removeItem(at: destination)
            sendHTTP(connection, code: 500, body: "Write failed")
            return
        }

        var receiveMore: (() -> Void)!
        let finish: (Bool) -> Void = { [weak self] success in
            try? handle.close()
            guard let self = self else { return }
            if success {
                let size = (try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? written
                DispatchQueue.main.async {
                    self.receivedFiles.insert(ReceivedFile(url: destination, name: destination.lastPathComponent, size: size, receivedAt: Date()), at: 0)
                    self.status = "تم استلام \(destination.lastPathComponent)"
                }
                self.sendHTTP(connection, code: 200, body: "OK")
            } else {
                try? FileManager.default.removeItem(at: destination)
                self.sendHTTP(connection, code: 500, body: "Receive failed")
            }
        }

        if expected >= 0 && written >= expected { finish(true); return }
        receiveMore = { [weak connection] in
            guard let connection = connection else { return }
            connection.receive(minimumIncompleteLength: 1, maximumLength: 128 * 1024) { data, _, complete, error in
                if let data = data, !data.isEmpty {
                    do { try handle.write(contentsOf: data); written += Int64(data.count) }
                    catch { finish(false); return }
                }
                if error != nil { finish(false) }
                else if (expected >= 0 && written >= expected) || complete { finish(true) }
                else { receiveMore() }
            }
        }
        receiveMore()
    }

    private func sendHTTP(_ connection: NWConnection, code: Int, body: String, contentType: String = "text/plain; charset=utf-8") {
        let reason: String
        switch code { case 200: reason = "OK"; case 204: reason = "No Content"; case 400: reason = "Bad Request"; case 404: reason = "Not Found"; case 405: reason = "Method Not Allowed"; default: reason = "Internal Server Error" }
        let data = Data(body.utf8)
        let response = "HTTP/1.1 \(code) \(reason)\r\nContent-Length: \(data.count)\r\nContent-Type: \(contentType)\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type,X-File-Name,X-File-Size,X-SVLN-Sender-ID,X-SVLN-Sender-Name\r\nConnection: close\r\n\r\n"
        var packet = Data(response.utf8); packet.append(data)
        connection.send(content: packet, completion: .contentProcessed { _ in connection.cancel() })
    }

    private func startDiscoveryResponder() {
        guard !discoveryRunning else { return }
        discoveryRunning = true
        discoveryQueue.async { [weak self] in
            guard let self = self else { return }
            let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            guard fd >= 0 else { DispatchQueue.main.async { self.status = "تعذر تشغيل اكتشاف الأجهزة" }; return }
            self.discoveryFD = fd
            var yes: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
            var address = sockaddr_in(); address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); address.sin_family = sa_family_t(AF_INET); address.sin_port = self.discoveryPort.bigEndian; address.sin_addr = in_addr(s_addr: INADDR_ANY.bigEndian)
            let bindResult = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
            guard bindResult == 0 else {
                Darwin.close(fd); self.discoveryFD = -1; self.discoveryRunning = false
                DispatchQueue.main.async { self.status = "تعذر فتح منفذ الاكتشاف 5052" }
                return
            }
            var buffer = [UInt8](repeating: 0, count: 2048)
            while self.discoveryRunning {
                var sender = sockaddr_in(); var senderLength = socklen_t(MemoryLayout<sockaddr_in>.size)
                let count: Int = withUnsafeMutablePointer(to: &sender) { senderPtr in senderPtr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in buffer.withUnsafeMutableBytes { raw in Darwin.recvfrom(fd, raw.baseAddress, raw.count, 0, sa, &senderLength) } } }
                if count <= 0 { continue }
                let message = String(decoding: buffer[0..<count], as: UTF8.self)
                if message.hasPrefix("SVLN_DISCOVER|") {
                    let ip = Self.bestLocalIPv4() ?? "0.0.0.0"
                    guard ip != "0.0.0.0" else { continue }
                    let reply = "SVLN_DEVICE|\(Self.cleanProtocol(self.deviceName))|ios|\(ip)|5051|\(self.deviceId)"
                    self.sendUDP(reply, to: sender)
                } else if message.hasPrefix("SVLN_DEVICE|") { self.acceptDiscoveryMessage(message) }
            }
        }
    }

    private func sendUDP(_ message: String, host: String, port: UInt16) {
        let fd = discoveryFD; guard fd >= 0 else { return }
        var target = sockaddr_in(); target.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); target.sin_family = sa_family_t(AF_INET); target.sin_port = port.bigEndian
        inet_pton(AF_INET, host, &target.sin_addr)
        let data = Array(message.utf8)
        _ = withUnsafePointer(to: &target) { ptr in ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in data.withUnsafeBytes { raw in Darwin.sendto(fd, raw.baseAddress, raw.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size)) } } }
    }

    private func sendUDP(_ message: String, to targetValue: sockaddr_in) {
        let fd = discoveryFD; guard fd >= 0 else { return }
        var target = targetValue; let data = Array(message.utf8)
        _ = withUnsafePointer(to: &target) { ptr in ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in data.withUnsafeBytes { raw in Darwin.sendto(fd, raw.baseAddress, raw.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size)) } } }
    }

    private func acceptDiscoveryMessage(_ message: String) {
        let parts = message.components(separatedBy: "|"); guard parts.count >= 5 else { return }
        let name = parts[1].isEmpty ? "جهاز" : parts[1]; let type = parts[2].isEmpty ? "device" : parts[2]; let ip = parts[3]; let port = Int(parts[4]) ?? 5051
        let id = parts.count > 5 && !parts[5].isEmpty ? parts[5] : "\(ip):\(port)"
        guard id != deviceId, Self.isIPv4(ip) else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if let index = self.devices.firstIndex(where: { $0.id == id || ($0.ip == ip && $0.port == port) }) {
                let selected = self.devices[index].selected
                self.devices[index] = LocalDevice(id: id, name: name, type: type, ip: ip, port: port, selected: selected, lastSeen: Date())
            } else { self.devices.append(LocalDevice(id: id, name: name, type: type, ip: ip, port: port, selected: true, lastSeen: Date())) }
        }
    }

    private static func receiveFolder() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let folder = docs.appendingPathComponent("SendViaLocalNet", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    private static func uniqueURL(in folder: URL, name: String) -> URL {
        let safe = safeFileName(name); var candidate = folder.appendingPathComponent(safe)
        if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        let ext = candidate.pathExtension; let stem = candidate.deletingPathExtension().lastPathComponent; var index = 2
        while true {
            let newName = ext.isEmpty ? "\(stem) (\(index))" : "\(stem) (\(index)).\(ext)"
            candidate = folder.appendingPathComponent(newName)
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            index += 1
        }
    }

    private static func safeFileName(_ value: String) -> String {
        let cleaned = value.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "\\", with: "_").replacingOccurrences(of: "\0", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "received-file" : String(cleaned.prefix(180))
    }
    private static func cleanProtocol(_ value: String) -> String { value.replacingOccurrences(of: "|", with: " ").replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\r", with: " ") }
    private static func jsonEscape(_ value: String) -> String { value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") }
    private static func subnetBroadcast(for ip: String) -> String? { guard isIPv4(ip), let dot = ip.lastIndex(of: ".") else { return nil }; return String(ip[...dot]) + "255" }
    private static func isIPv4(_ value: String) -> Bool { let parts = value.split(separator: "."); guard parts.count == 4 else { return false }; return parts.allSatisfy { (Int($0) ?? -1) >= 0 && (Int($0) ?? 256) <= 255 } }

    private static func bestLocalIPv4() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?; guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }; defer { freeifaddrs(interfaces) }
        var pointer: UnsafeMutablePointer<ifaddrs>? = first; var fallback: String?
        while let item = pointer?.pointee {
            defer { pointer = item.ifa_next }
            guard let address = item.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: item.ifa_name); if name == "lo0" { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST)); var copy = address.pointee
            let result = withUnsafePointer(to: &copy) { ptr in getnameinfo(ptr, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) }
            guard result == 0 else { continue }; let ip = String(cString: host)
            if name == "en0" && isIPv4(ip) { return ip }
            if fallback == nil && isIPv4(ip) && !ip.hasPrefix("127.") { fallback = ip }
        }
        return fallback
    }
}
