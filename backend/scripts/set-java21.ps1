$install = 'C:\Users\princ\Downloads\jdk-21_windows-x64_bin\jdk-21.0.11'
Write-Host "Setting USER JAVA_HOME to $install"
[Environment]::SetEnvironmentVariable('JAVA_HOME',$install,'User')
$userPath = [Environment]::GetEnvironmentVariable('Path','User')
if ($userPath -notlike "*$install*") {
    [Environment]::SetEnvironmentVariable('Path',$userPath + ';' + $install + '\\bin','User')
    Write-Host "Appended to user PATH"
} else {
    Write-Host "User PATH already contains JDK path"
}
# apply to current process
$env:JAVA_HOME = $install
$env:PATH = $install + '\\bin;' + $env:PATH
Write-Host 'java -version:'
try { java -version 2>&1 | ForEach-Object { Write-Host $_ } } catch { Write-Host 'java not on PATH' }
Write-Host 'javac -version:'
try { javac -version 2>&1 | ForEach-Object { Write-Host $_ } } catch { Write-Host 'javac not on PATH' }
Write-Host 'Script complete.'
