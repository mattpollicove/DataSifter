param(
    [string]$WatchDir = "./data/inbox",
    [string]$Endpoint = "http://localhost:8080",
    [string]$WorkflowId = $env:DATASIFTER_WORKFLOW_ID,
    [int]$PollIntervalSeconds = 10
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($WorkflowId)) {
    throw 'Set DATASIFTER_WORKFLOW_ID to the workflow receiving these CSV files.'
}
if ([string]::IsNullOrWhiteSpace($env:DATASIFTER_USERNAME) -or [string]::IsNullOrWhiteSpace($env:DATASIFTER_PASSWORD)) {
    throw 'Set DATASIFTER_USERNAME and DATASIFTER_PASSWORD for an operator account.'
}

New-Item -ItemType Directory -Force -Path $WatchDir | Out-Null
$credentials = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($env:DATASIFTER_USERNAME):$($env:DATASIFTER_PASSWORD)"))
$handler = [Net.Http.HttpClientHandler]::new()
$client = [Net.Http.HttpClient]::new($handler)
$client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Basic', $credentials)

Write-Host "DataSifter CSV file agent started"
Write-Host "Watching $WatchDir"

while ($true) {
    foreach ($file in Get-ChildItem -Path $WatchDir -File -Filter '*.csv' -ErrorAction SilentlyContinue) {
        $csrfResponse = $client.GetAsync("$($Endpoint.TrimEnd('/'))/api/csrf").GetAwaiter().GetResult()
        $csrfResponse.EnsureSuccessStatusCode()
        $csrf = $csrfResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $client.DefaultRequestHeaders.Remove('X-XSRF-TOKEN') | Out-Null
        $client.DefaultRequestHeaders.Add('X-XSRF-TOKEN', $csrf.token)

        $form = [Net.Http.MultipartFormDataContent]::new()
        $stream = [IO.File]::OpenRead($file.FullName)
        $content = [Net.Http.StreamContent]::new($stream)
        $content.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('text/csv')
        $form.Add($content, 'file', $file.Name)
        try {
            $response = $client.PostAsync(
                "$($Endpoint.TrimEnd('/'))/api/workflows/$WorkflowId/csv",
                $form).GetAwaiter().GetResult()
            $response.EnsureSuccessStatusCode()
        } finally {
            $form.Dispose()
            $stream.Dispose()
        }
        Move-Item -Path $file.FullName -Destination "$($file.FullName).processed" -Force
        Write-Host "Uploaded $($file.Name) to workflow $WorkflowId"
    }
    Start-Sleep -Seconds $PollIntervalSeconds
}
