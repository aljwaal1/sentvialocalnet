$ErrorActionPreference = 'SilentlyContinue'

# Newer versions listen only on localhost and close themselves cleanly.
try {
    $client = New-Object System.Net.Sockets.TcpClient
    $async = $client.BeginConnect('127.0.0.1', 5053, $null, $null)
    if ($async.AsyncWaitHandle.WaitOne(900, $false)) {
        $client.EndConnect($async)
        $stream = $client.GetStream()
        $bytes = [System.Text.Encoding]::UTF8.GetBytes("SHUTDOWN`n")
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        Start-Sleep -Milliseconds 300
        $stream.Close()
    }
    $client.Close()
} catch {
}

# Give the program a few seconds to release its EXE.
$deadline = (Get-Date).AddSeconds(4)
while ((Get-Process -Name 'SendViaLocalNet' -ErrorAction SilentlyContinue) -and ((Get-Date) -lt $deadline)) {
    Start-Sleep -Milliseconds 200
}

# First upgrade from older versions: force-close only as a fallback.
Get-Process -Name 'SendViaLocalNet' -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500
