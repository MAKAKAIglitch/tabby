$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$wrapper = Join-Path $projectRoot 'gradlew.bat'
$propertiesPath = Join-Path $projectRoot 'gradle.properties'
$versionMatch = Select-String -LiteralPath $propertiesPath -Pattern '^minecraft_version=(.+)$' | Select-Object -First 1
if (-not $versionMatch) { throw "minecraft_version was not found in gradle.properties" }
$minecraftVersion = $versionMatch.Matches[0].Groups[1].Value.Trim()
$outputJar = Join-Path $projectRoot ("dist\Tabby-$minecraftVersion.jar")
$logPath = Join-Path $projectRoot 'build-tabby.log'

if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    [Console]::Error.WriteLine("Tabby build failed: gradlew.bat was not found in $projectRoot")
    exit 1
}

Push-Location $projectRoot
try {
    $env:TABBY_OPEN_MOD_FOLDER = '0'
    Write-Host ''
    Write-Host '============================================================'
    Write-Host ' Tabby local build'
    Write-Host '============================================================'
    Write-Host "Project : $projectRoot"
    Write-Host "Target  : Fabric Loader - Minecraft Java Edition $minecraftVersion"
    Write-Host 'Java    : automatically select an installed Java 21 JDK'
    Write-Host "Log     : $logPath"
    Write-Host ''

    & $wrapper build exportModJar --console=plain 2>&1 | Tee-Object -FilePath $logPath
    $gradleExitCode = $LASTEXITCODE
    if ($null -eq $gradleExitCode -or $gradleExitCode -ne 0) {
        throw "Gradle exited with code $gradleExitCode. See build-tabby.log for details."
    }
    if (-not (Test-Path -LiteralPath $outputJar -PathType Leaf)) {
        throw "Gradle reported success, but the expected JAR was not created: $outputJar"
    }
    $jarInfo = Get-Item -LiteralPath $outputJar
    if ($jarInfo.Length -le 0) { throw "The exported JAR is empty: $outputJar" }

    Write-Host ''
    Write-Host 'BUILD SUCCESSFUL'
    Write-Host "Loader  : Fabric (Minecraft $minecraftVersion)"
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
