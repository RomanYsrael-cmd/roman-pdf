param(
    [string]$Serial = 'adb-HA1PES51-vnbpuR._adb-tls-connect._tcp',
    [int]$Operations = 1000,
    [int]$SampleEvery = 2,
    [int]$MemoryEvery = 20,
    [int]$SettledDelayMs = 260,
    [int]$Seed = 20260916,
    [string]$CaptureDirectory = ''
)

$ErrorActionPreference = 'Stop'
$stressPackage = 'com.romanysrael.romanpdf.debug'
$drawingAssembly = Add-Type -AssemblyName System.Drawing

if ([string]::IsNullOrWhiteSpace($CaptureDirectory)) {
    $CaptureDirectory = Join-Path (Get-Location) ('work\device-stress-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Force -Path $CaptureDirectory | Out-Null

function Invoke-Device([string[]]$Arguments) {
    & adb -s $Serial @Arguments 2>$null
}

function Get-TopActivity() {
    $activity = Invoke-Device @('shell', 'dumpsys', 'activity', 'activities') | Select-String 'mResumedActivity'
    return [string]$activity
}

function Get-ProcessId() {
    return [string](Invoke-Device @('shell', 'pidof', $stressPackage)).Trim()
}

function Get-PssKb() {
    $line = Invoke-Device @('shell', 'dumpsys', 'meminfo', $stressPackage) | Select-String 'TOTAL PSS:' | Select-Object -First 1
    if ($line -and $line.ToString() -match 'TOTAL PSS:\s+(\d+)') { return [int]$Matches[1] }
    return $null
}

function Capture-Screenshot([string]$Path) {
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = 'adb'
    $startInfo.Arguments = "-s `"$Serial`" exec-out screencap -p"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create)
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $process.WaitForExit()
}

function Get-ReaderStats([string]$Path) {
    $bitmap = New-Object System.Drawing.Bitmap($Path)
    $dark = 0
    $bright = 0
    $total = 0
    try {
        for ($y = 88; $y -lt 1180; $y += 4) {
            for ($x = 0; $x -lt 800; $x += 4) {
                $pixel = $bitmap.GetPixel($x, $y)
                $total++
                if ($pixel.R -lt 35 -and $pixel.G -lt 40 -and $pixel.B -lt 45) { $dark++ }
                if ($pixel.R -gt 180 -and $pixel.G -gt 180 -and $pixel.B -gt 180) { $bright++ }
            }
        }
    } finally { $bitmap.Dispose() }
    [pscustomobject]@{
        DarkPercent = [math]::Round(100 * $dark / $total, 2)
        BrightPercent = [math]::Round(100 * $bright / $total, 2)
    }
}

if (-not (Get-TopActivity).Contains('ReaderActivity')) {
    throw 'ReaderActivity is not in the foreground. Open a document before starting the stress run.'
}

Invoke-Device @('logcat', '-c') | Out-Null
$random = [System.Random]::new($Seed)
$blankEvents = 0
$crashSignals = 0
$samples = 0
$memorySamples = 0
$records = [System.Collections.Generic.List[object]]::new()

for ($operation = 1; $operation -le $Operations; $operation++) {
    $action = $random.Next(0, 12)
    switch ($action) {
        0 { Invoke-Device @('shell', 'input', 'tap', '740', '620') }
        1 { Invoke-Device @('shell', 'input', 'tap', '740', '420') }
        2 { Invoke-Device @('shell', 'input', 'tap', '60', '620') }
        3 { Invoke-Device @('shell', 'input', 'tap', '60', '420') }
        4 { Invoke-Device @('shell', 'input', 'swipe', '690', '620', '120', '620', '180') }
        5 { Invoke-Device @('shell', 'input', 'swipe', '120', '620', '690', '620', '180') }
        6 { Invoke-Device @('shell', 'input', 'tap', '740', '800') }
        7 { Invoke-Device @('shell', 'input', 'tap', '60', '800') }
        8 { Start-Sleep -Milliseconds 600 }
        9 {
            Invoke-Device @('shell', 'input', 'tap', '400', '620')
            Start-Sleep -Milliseconds 70
            Invoke-Device @('shell', 'input', 'tap', '400', '620')
        }
        10 { Invoke-Device @('shell', 'input', 'swipe', '720', '400', '80', '780', '220') }
        11 { Invoke-Device @('shell', 'input', 'swipe', '80', '780', '720', '400', '220') }
    }
    Start-Sleep -Milliseconds $SettledDelayMs

    $alive = -not [string]::IsNullOrWhiteSpace((Get-ProcessId))
    if (-not $alive) { $crashSignals++ }

    if ($operation % $SampleEvery -eq 0) {
        $screenshot = Join-Path $CaptureDirectory ("frame-{0:D4}.png" -f $operation)
        Capture-Screenshot $screenshot
        $stats = Get-ReaderStats $screenshot
        $isBlank = $stats.DarkPercent -gt 85 -and $stats.BrightPercent -lt 2
        if ($isBlank) { $blankEvents++ }
        $pss = $null
        if ($operation % $MemoryEvery -eq 0) {
            $pss = Get-PssKb
            $memorySamples++
        }
        $samples++
        $records.Add([pscustomobject]@{
            Operation = $operation
            DarkPercent = $stats.DarkPercent
            BrightPercent = $stats.BrightPercent
            BlankSignal = $isBlank
            ProcessAlive = $alive
            PssKb = $pss
        })
        if ($operation % 100 -eq 0) {
            "operation=$operation samples=$samples blankSignals=$blankEvents crashSignals=$crashSignals pssKb=$pss dark=$($stats.DarkPercent)% bright=$($stats.BrightPercent)%"
        }
    }
}

$records | Export-Csv (Join-Path $CaptureDirectory 'results.csv') -NoTypeInformation
$logcat = Invoke-Device @('logcat', '-d')
$logcat | Set-Content (Join-Path $CaptureDirectory 'logcat.txt')
$fatalLines = @($logcat | Select-String 'FATAL EXCEPTION|ANR in|RomanPdfRender')
if ($fatalLines.Count -eq 0) {
    'No FATAL EXCEPTION, ANR in, or RomanPdfRender lines found.' | Set-Content (Join-Path $CaptureDirectory 'reliability-log-lines.txt')
} else {
    $fatalLines | Set-Content (Join-Path $CaptureDirectory 'reliability-log-lines.txt')
}

[pscustomobject]@{
    CompletedOperations = $Operations
    Samples = $samples
    BlankSignals = $blankEvents
    CrashSignals = $crashSignals
    MemorySamples = $memorySamples
    CaptureDirectory = $CaptureDirectory
    ForegroundActivity = Get-TopActivity
    ReliabilityLogLines = $fatalLines.Count
} | Format-List
