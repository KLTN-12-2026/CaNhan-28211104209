Set-Location -LiteralPath $PSScriptRoot
$env:VITE_DATA_MODE = "api"
$env:VITE_API_BASE_URL = "/api/v1"
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort