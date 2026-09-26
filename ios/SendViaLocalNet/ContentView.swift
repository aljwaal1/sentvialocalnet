import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var service: LocalTransferService
    @State private var showImporter = false
    @State private var showFolderImporter = false
    @State private var showAllOnMyIPhoneImporter = false

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    statusCard
                    devicesCard
                    filesCard
                    receivedCard
                }
                .padding()
            }
            .navigationTitle("نقل محلي Pro")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { service.discover() } label: { Image(systemName: "arrow.clockwise") }
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): service.prepareFiles(urls)
                case .failure(let error): service.status = "تعذر اختيار الملفات: \(error.localizedDescription)"
                }
            }
            .fileImporter(isPresented: $showFolderImporter, allowedContentTypes: [.folder], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls):
                    if let folder = urls.first { service.addFolder(folder) }
                case .failure(let error): service.status = "تعذر اختيار المجلد: \(error.localizedDescription)"
                }
            }
            .fileImporter(isPresented: $showAllOnMyIPhoneImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls):
                    service.addPickedItems(urls)
                case .failure(let error):
                    service.status = "تعذر اختيار محتويات On My iPhone: \(error.localizedDescription)"
                }
            }
        }
        .navigationViewStyle(.stack)
        .environment(\.layoutDirection, .rightToLeft)
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label(service.receiverRunning ? "الاستقبال يعمل" : "الاستقبال متوقف", systemImage: service.receiverRunning ? "antenna.radiowaves.left.and.right" : "exclamationmark.triangle")
                    .foregroundColor(service.receiverRunning ? .green : .orange)
                Spacer()
                Text(service.localIP).font(.caption.monospaced())
            }
            Text("إرسال ⇄ استقبال باتجاهين").font(.caption).fontWeight(.semibold)
            Text(service.status).font(.subheadline).foregroundColor(.secondary)
            if service.sending {
                ProgressView(value: service.progress)
            }
        }
        .cardStyle()
    }

    private var devicesCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("الأجهزة القريبة").font(.headline)
                Spacer()
                Button("بحث الآن") { service.discover() }.buttonStyle(.bordered)
            }
            if service.devices.isEmpty {
                Text("افتح التطبيق على Android أو Windows أو iPhone آخر، وتأكد أن الأجهزة على نفس Wi‑Fi.")
                    .font(.subheadline).foregroundColor(.secondary)
            } else {
                ForEach(service.devices) { device in
                    Button { service.toggleDevice(device.id) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: icon(for: device.type))
                                .frame(width: 34, height: 34)
                                .background(Color.accentColor.opacity(0.1))
                                .clipShape(RoundedRectangle(cornerRadius: 9))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.name).fontWeight(.semibold)
                                Text("\(device.ip):\(device.port) • \(device.type.uppercased())")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                            Image(systemName: device.selected ? "checkmark.circle.fill" : "circle")
                                .foregroundColor(device.selected ? Color.accentColor : .secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
        }
        .cardStyle()
    }

    private var filesCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("الإرسال").font(.headline)
            HStack {
                Button { showImporter = true } label: { Label("ملف / ملفات", systemImage: "doc.badge.plus") }
                    .buttonStyle(.borderedProminent)
                Button { showFolderImporter = true } label: { Label("إضافة مجلد", systemImage: "folder.fill.badge.plus") }
                    .buttonStyle(.bordered)
                if !service.pendingFiles.isEmpty {
                    Button("مسح") { service.clearPendingFiles() }.buttonStyle(.bordered)
                }
            }

            Button { showAllOnMyIPhoneImporter = true } label: {
                Label("اختيار كل محتويات On My iPhone", systemImage: "iphone.gen3")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            if service.pendingFiles.isEmpty {
                VStack(alignment: .leading, spacing: 5) {
                    Text("لإرسال كل ما يظهر لك داخل On My iPhone اضغط الزر بالأعلى، ثم افتح On My iPhone واختر كل الملفات والمجلدات التي يسمح iOS بتحديدها.").font(.subheadline).foregroundColor(.secondary)
                    Text("iOS لا يسمح للتطبيق بالدخول تلقائيًا إلى بيانات التطبيقات المخفية؛ يمكن إرسال فقط العناصر الظاهرة في Files والتي تمنحها للتطبيق.").font(.caption).foregroundColor(.secondary)
                }
            } else {
                if !service.selectedFolders.isEmpty {
                    Text("المجلدات المختارة").font(.subheadline).fontWeight(.semibold)
                    ForEach(service.selectedFolders) { folder in
                        HStack(spacing: 10) {
                            Button { service.toggleFolder(folder.id) } label: {
                                Image(systemName: folder.selected ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(folder.selected ? .accentColor : .secondary)
                                    .font(.title3)
                            }
                            .buttonStyle(.plain)
                            Image(systemName: "folder.fill").foregroundColor(.accentColor)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(folder.name).fontWeight(.semibold).lineLimit(1)
                                Text("\(folder.itemCount) عنصر").font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) { service.removeFolder(folder.id) } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.vertical, 4)
                        Divider()
                    }
                }

                let looseFiles = service.pendingFiles.filter { $0.rootFolderId == nil }
                if !looseFiles.isEmpty {
                    Text("الملفات").font(.subheadline).fontWeight(.semibold)
                    ForEach(looseFiles) { file in
                        HStack {
                            Image(systemName: "doc")
                            Text(file.name).lineLimit(1)
                            Spacer()
                            Text(formatBytes(file.size)).font(.caption).foregroundColor(.secondary)
                        }
                    }
                }

                Button { service.sendSelected() } label: {
                    Label(service.sending ? "جاري الإرسال…" : "إرسال إلى الأجهزة المحددة", systemImage: "paperplane.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(service.sending)
            }
        }
        .cardStyle()
    }

    private var receivedCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("الاستقبال من Windows / Android / iPhone").font(.headline)
            if service.receivedFiles.isEmpty {
                Text("ستظهر الملفات التي تصل إلى هذا iPhone هنا، وتُحفظ داخل Files > On My iPhone > نقل محلي Pro.")
                    .font(.subheadline).foregroundColor(.secondary)
            } else {
                ForEach(service.receivedFiles) { file in
                    HStack {
                        Image(systemName: "tray.and.arrow.down.fill").foregroundColor(.green)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(file.name).lineLimit(1)
                            Text(formatBytes(file.size)).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        Text("Files").font(.caption).foregroundColor(.secondary)
                    }
                    Divider()
                }
            }
        }
        .cardStyle()
    }

    private func icon(for type: String) -> String {
        switch type.lowercased() {
        case "ios", "iphone", "ipad": return "iphone"
        case "android": return "apps.iphone"
        case "windows", "pc", "desktop": return "desktopcomputer"
        default: return "network"
        }
    }

    private func formatBytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }
}

private extension View {
    func cardStyle() -> some View {
        self
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.secondary.opacity(0.15)))
    }
}
