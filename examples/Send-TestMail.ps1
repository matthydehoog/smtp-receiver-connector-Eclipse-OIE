<#
.SYNOPSIS
    Sends one test mail to the SMTP Receiver of an Eclipse OIE channel.

.EXAMPLE
    .\Send-TestMail.ps1 -Port 2525
    Asks for the user name and password and sends a mail through localhost:2525.

.EXAMPLE
    .\Send-TestMail.ps1 -Port 2525 -Credential (Get-Credential mirth)

.EXAMPLE
    .\Send-TestMail.ps1 -Port 2525 -NoLogin
    For a receiver that does not require a login.

.EXAMPLE
    .\Send-TestMail.ps1 -Port 587 -Tls -Credential (Get-Credential mirth)
    STARTTLS. A certificate that is not trusted by this machine is refused; that is the client doing its job.
#>
param(
    [string]$Server = 'localhost',
    [int]$Port = 2525,
    [pscredential]$Credential,
    [switch]$NoLogin,
    [switch]$Tls,
    [string]$From = 'sender@example.org',
    [string]$To = 'intake@example.org',
    [string]$Subject = "Test from Send-TestMail.ps1 $(Get-Date -Format s)",
    [string]$Body = "Hello,`r`n`r`nthis is a test mail with an empty line and a line that starts with a dot:`r`n.dot`r`n`r`nGroeten"
)

$client = New-Object System.Net.Mail.SmtpClient($Server, $Port)
$client.EnableSsl = [bool]$Tls          # STARTTLS
$client.Timeout = 15000

if (-not $NoLogin) {
    if (-not $Credential) { $Credential = Get-Credential -Message "Login for ${Server}:${Port}" }
    $client.Credentials = $Credential.GetNetworkCredential()
}

$message = New-Object System.Net.Mail.MailMessage($From, $To, $Subject, $Body)
try {
    $client.Send($message)
    Write-Host "Sent through ${Server}:${Port}." -ForegroundColor Green
}
catch {
    Write-Host "Failed: $($_.Exception.GetBaseException().Message)" -ForegroundColor Red
    exit 1
}
finally {
    $message.Dispose()
    $client.Dispose()
}
