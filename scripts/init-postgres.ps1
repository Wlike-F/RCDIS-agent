param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 5432,
    [string]$Database = "rcdis_agent",
    [string]$Username = "postgres",
    [string]$SchemaFile = "docs/database-schema.sql",
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\18\bin\psql.exe"
)

$ErrorActionPreference = "Stop"

if ($Database -notmatch "^[A-Za-z0-9_]+$") {
    throw "Database name contains unsupported characters: $Database"
}

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    $PsqlPath = "C:\Program Files\PostgreSQL\18\pgAdmin 4\runtime\psql.exe"
}

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    throw "psql.exe was not found. Set -PsqlPath to your PostgreSQL psql.exe path."
}

if (-not (Test-Path -LiteralPath $SchemaFile)) {
    throw "Schema file was not found: $SchemaFile"
}

if ([string]::IsNullOrWhiteSpace($env:PGPASSWORD)) {
    throw "Set PGPASSWORD before running this script."
}

$databaseExists = & $PsqlPath -h $HostName -p $Port -U $Username -d postgres -v ON_ERROR_STOP=1 -tAc "SELECT 1 FROM pg_database WHERE datname = '$Database';"

if ($LASTEXITCODE -ne 0) {
    throw "Failed to check whether database exists: $Database"
}

if ([string]::IsNullOrWhiteSpace($databaseExists)) {
    $createDatabaseSql = 'CREATE DATABASE "' + $Database + '";'
    & $PsqlPath -h $HostName -p $Port -U $Username -d postgres -v ON_ERROR_STOP=1 -c $createDatabaseSql

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create database: $Database"
    }
}

& $PsqlPath -h $HostName -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -f $SchemaFile

if ($LASTEXITCODE -ne 0) {
    throw "Failed to apply schema file: $SchemaFile"
}

& $PsqlPath -h $HostName -p $Port -U $Username -d $Database -v ON_ERROR_STOP=1 -c "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;"

if ($LASTEXITCODE -ne 0) {
    throw "Failed to list public tables for database: $Database"
}
