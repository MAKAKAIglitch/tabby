param()

$ErrorActionPreference = 'Stop'

function Test-Java21Home([string] $HomePath) {
    if ([string]::IsNullOrWhiteSpace($HomePath)) { return $false }
    $candidate = $HomePath.Trim().Trim('"')
    $javaExe = Join-Path $candidate 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) { return $false }

    $versionText = (& $javaExe -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { return $false }
    return ($versionText -match '(?m)version\s+"21(?:\.|")|openjdk\s+21(?:\.|\s)')
}

$candidates = New-Object 'System.Collections.Generic.List[string]'
foreach ($candidateHome in @($env:TABBY_JAVA_HOME, $env:JAVA_HOME, $env:JDK_HOME)) {
    if (-not [string]::IsNullOrWhiteSpace($candidateHome)) { $candidates.Add($candidateHome.Trim().Trim('"')) }
}

foreach ($commandName in @('java.exe', 'java')) {
    $command = Get-Command $commandName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command -and $command.Source) {
        $candidates.Add((Split-Path (Split-Path $command.Source -Parent) -Parent))
    }
}

$registryRoots = @(
    'HKLM:\SOFTWARE\JavaSoft\JDK',
    'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
    'HKLM:\SOFTWARE\Eclipse Adoptium\JDK',
    'HKLM:\SOFTWARE\Microsoft\JDK',
    'HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK'
)
foreach ($root in $registryRoots) {
    if (Test-Path $root) {
        $keys = @()
        $keys += Get-Item -LiteralPath $root -ErrorAction SilentlyContinue
        $keys += Get-ChildItem -LiteralPath $root -ErrorAction SilentlyContinue
        foreach ($key in $keys) {
            if ($key) {
                $properties = Get-ItemProperty -LiteralPath $key.PSPath -ErrorAction SilentlyContinue
                if ($properties -and $properties.JavaHome) { $candidates.Add([string]$properties.JavaHome) }
            }
        }
    }
}

$roots = @(
    $env:ProgramFiles,
    ${env:ProgramFiles(x86)},
    $env:LOCALAPPDATA
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
$relativePatterns = @(
    'Eclipse Adoptium\jdk-21*',
    'Microsoft\jdk-21*',
    'Java\jdk-21*',
    'Amazon Corretto\jdk-21*',
    'Amazon Corretto\jdk21*',
    'Zulu\zulu-21*',
    'BellSoft\LibericaJDK-21*',
    'Programs\Eclipse Adoptium\jdk-21*',
    'Programs\Microsoft\jdk-21*',
    'Programs\Amazon Corretto\jdk-21*'
)
foreach ($root in $roots) {
    foreach ($pattern in $relativePatterns) {
        $fullPattern = Join-Path $root $pattern
        Get-ChildItem -Path $fullPattern -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $candidates.Add($_.FullName)
        }
    }
}

$gradleHome = $env:GRADLE_USER_HOME
if ([string]::IsNullOrWhiteSpace($gradleHome)) {
    $gradleHome = Join-Path $HOME '.gradle'
}
$jdkHome = Join-Path $gradleHome 'jdks\tabby-temurin-21'
$candidates.Add($jdkHome)

$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
foreach ($candidate in $candidates) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $normalized = $candidate.Trim().Trim('"')
    if (-not $seen.Add($normalized)) { continue }
    if (Test-Java21Home $normalized) {
        [Console]::Out.WriteLine((Resolve-Path -LiteralPath $normalized).Path)
        exit 0
    }
}

$architecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
switch -Regex ($architecture) {
    '^(AMD64|x64)$' { $adoptiumArchitecture = 'x64'; break }
    '^(ARM64|AARCH64)$' { $adoptiumArchitecture = 'aarch64'; break }
    default {
        [Console]::Error.WriteLine("Tabby: no automatic Temurin 21 bootstrap mapping for Windows architecture '$architecture'.")
        exit 1
    }
}

$parentDirectory = Join-Path $gradleHome 'jdks'
New-Item -ItemType Directory -Path $parentDirectory -Force | Out-Null
$tempDirectory = Join-Path $parentDirectory ('.tabby-temurin-21-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDirectory -Force | Out-Null

try {
    [Console]::Error.WriteLine('Tabby: no installed Java 21 found; downloading a SHA-256-verified Eclipse Temurin JDK 21 into Gradle''s cache.')
    $api = "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$adoptiumArchitecture&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse"
    $assets = @(Invoke-RestMethod -Uri $api -Method Get -TimeoutSec 45)
    if ($assets.Count -lt 1 -or -not $assets[0].binary.package.link -or -not $assets[0].binary.package.checksum) {
        throw 'Adoptium did not return a JDK download URL and SHA-256 checksum.'
    }

    $package = $assets[0].binary.package
    $archive = Join-Path $tempDirectory 'temurin21.zip'
    $extractDirectory = Join-Path $tempDirectory 'extracted'
    New-Item -ItemType Directory -Path $extractDirectory -Force | Out-Null
    Invoke-WebRequest -Uri $package.link -OutFile $archive -TimeoutSec 300 -UseBasicParsing

    $actualChecksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $expectedChecksum = ([string]$package.checksum).ToLowerInvariant()
    if ($actualChecksum -ne $expectedChecksum) {
        throw "JDK checksum verification failed. Expected $expectedChecksum, received $actualChecksum."
    }

    Expand-Archive -LiteralPath $archive -DestinationPath $extractDirectory -Force
    $jdkRoot = Get-ChildItem -LiteralPath $extractDirectory -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') -PathType Leaf } |
        Select-Object -First 1
    if (-not $jdkRoot) { throw 'The downloaded archive did not contain a JDK java.exe.' }

    if (Test-Path -LiteralPath $jdkHome) {
        Remove-Item -LiteralPath $jdkHome -Recurse -Force
    }
    Move-Item -LiteralPath $jdkRoot.FullName -Destination $jdkHome
    if (-not (Test-Java21Home $jdkHome)) {
        Remove-Item -LiteralPath $jdkHome -Recurse -Force -ErrorAction SilentlyContinue
        throw 'The downloaded JDK failed the Java 21 version verification.'
    }

    [Console]::Out.WriteLine((Resolve-Path -LiteralPath $jdkHome).Path)
    exit 0
}
catch {
    [Console]::Error.WriteLine("Tabby: automatic Java 21 setup failed: $($_.Exception.Message)")
    exit 1
}
finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}
