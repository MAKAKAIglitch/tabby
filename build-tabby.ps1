$ErrorActionPreference = 'Continue'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$wrapper = Join-Path $projectRoot 'gradlew.bat'
$outputJar = Join-Path $projectRoot 'dist\Tabby-1.21.4.jar'
$logPath = Join-Path $projectRoot 'build-tabby.log'

if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    [Console]::Error.WriteLine("Tabby build failed: gradlew.bat was not found in $projectRoot")
    exit 1
}

Push-Location $projectRoot
try {
    # The Gradle build already has an auto-selecting, checksum-verifying Java 21 bootstrap.
    # Avoid opening build/libs; the launcher opens the final dist folder instead.
    $env:TABBY_OPEN_MOD_FOLDER = '0'

    Write-Host ''
    Write-Host '============================================================'
    Write-Host ' Tabby local build'
    Write-Host '============================================================'
    Write-Host "Project : $projectRoot"
    Write-Host 'Java    : automatically select an installed Java 21 JDK'
    Write-Host "Log     : $logPath"
    Write-Host ''

    & $wrapper build exportModJar --console=plain 2>&1 | Tee-Object -FilePath $logPath
    $gradleExitCode = $LASTEXITCODE

    if ($null -eq $gradleExitCode) {
        $gradleExitCode = 1
    }
    if ($gradleExitCode -ne 0) {
        throw "Gradle exited with code $gradleExitCode. See build-tabby.log for details."
    }

    if (-not (Test-Path -LiteralPath $outputJar -PathType Leaf)) {
        throw "Gradle reported success, but the expected JAR was not created: $outputJar"
    }

    $jarInfo = Get-Item -LiteralPath $outputJar
    if ($jarInfo.Length -le 0) {
        throw "The exported JAR exists but is empty: $outputJar"
    }

    Write-Host ''
    Write-Host 'BUILD SUCCESSFUL'
    Write-Host "JAR     : $($jarInfo.FullName)"
    Write-Host ('Size    : {0:N0} bytes' -f $jarInfo.Length)
    Write-Host "Log     : $logPath"
    exit 0
}
catch {
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('BUILD FAILED')
    [Console]::Error.WriteLine($_.Exception.Message)
    [Console]::Error.WriteLine("Full build output: $logPath")
    exit 1
}
finally {
    Pop-Location
}
