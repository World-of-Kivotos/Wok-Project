param(
    [string]$RegistryPath = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($RegistryPath)) {
    $RegistryPath = Join-Path $repoRoot "docs\modules\module-registry.json"
}
$RegistryPath = (Resolve-Path $RegistryPath).Path
$registry = Get-Content -LiteralPath $RegistryPath -Raw -Encoding UTF8 | ConvertFrom-Json

if ($registry.schemaVersion -ne 2) {
    throw "Unsupported WOK module registry schemaVersion: $($registry.schemaVersion)"
}
if ($registry.product -ne "WOK") {
    throw "Module registry product must be WOK, got: $($registry.product)"
}

$properties = @{}
Get-Content -LiteralPath (Join-Path $repoRoot "gradle.properties") -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
        $properties[$Matches[1]] = $Matches[2]
    }
}
if ($registry.compatibilityModId -ne $properties["mod_id"]) {
    throw "Registry compatibilityModId '$($registry.compatibilityModId)' does not match mod_id '$($properties['mod_id'])'"
}

$modulesById = @{}
$owners = [System.Collections.Generic.List[object]]::new()
foreach ($module in $registry.modules) {
    $id = [string]$module.id
    if ($id -notmatch '^wok-[a-z0-9-]+$') {
        throw "Invalid WOK module ID: $id"
    }
    if ($modulesById.ContainsKey($id)) {
        throw "Duplicate WOK module ID: $id"
    }
    if ([string]::IsNullOrWhiteSpace([string]$module.name) -or
        [string]::IsNullOrWhiteSpace([string]$module.category) -or
        $module.currentPaths.Count -eq 0 -or
        $module.javaPackagePrefixes.Count -eq 0) {
        throw "Module $id must declare name, category, currentPaths and javaPackagePrefixes"
    }
    $modulesById[$id] = $module
    foreach ($prefix in $module.javaPackagePrefixes) {
        $owners.Add([PSCustomObject]@{ ModuleId = $id; Prefix = [string]$prefix })
    }
}
$owners = @($owners | Sort-Object { $_.Prefix.Length } -Descending)

foreach ($module in $registry.modules) {
    foreach ($dependency in $module.dependencies) {
        $dependencyId = [string]$dependency
        if ($dependencyId -eq [string]$module.id) {
            throw "Module $($module.id) cannot depend on itself"
        }
        if (-not $modulesById.ContainsKey($dependencyId)) {
            throw "Module $($module.id) references unknown dependency $dependencyId"
        }
    }
}

$visitState = @{}
function Visit-ModuleDependency {
    param([string]$ModuleId)
    $state = if ($visitState.ContainsKey($ModuleId)) { $visitState[$ModuleId] } else { 0 }
    if ($state -eq 1) {
        throw "WOK module dependency cycle detected at $ModuleId"
    }
    if ($state -eq 2) {
        return
    }
    $visitState[$ModuleId] = 1
    foreach ($dependency in $modulesById[$ModuleId].dependencies) {
        Visit-ModuleDependency -ModuleId ([string]$dependency)
    }
    $visitState[$ModuleId] = 2
}
foreach ($moduleId in $modulesById.Keys) {
    Visit-ModuleDependency -ModuleId $moduleId
}

$allowedExceptions = [System.Collections.Generic.HashSet[string]]::new()
foreach ($exception in $registry.boundaryExceptions) {
    $source = [string]$exception.source
    $target = [string]$exception.target
    if (-not $modulesById.ContainsKey($source) -or -not $modulesById.ContainsKey($target)) {
        throw "Boundary exception references unknown modules: $source -> $target"
    }
    if (@($modulesById[$source].dependencies) -contains $target) {
        throw "Boundary exception $source -> $target is redundant because the dependency is declared"
    }
    if ([string]::IsNullOrWhiteSpace([string]$exception.reason) -or
        [string]::IsNullOrWhiteSpace([string]$exception.exit)) {
        throw "Boundary exception $source -> $target must declare reason and exit"
    }
    if (-not $allowedExceptions.Add("$source->$target")) {
        throw "Duplicate boundary exception: $source -> $target"
    }
}

function Get-ModuleOwner {
    param([string]$QualifiedName)
    foreach ($owner in $owners) {
        if ($QualifiedName -eq $owner.Prefix -or $QualifiedName.StartsWith($owner.Prefix + ".")) {
            return $owner.ModuleId
        }
    }
    return $null
}

$observedExceptions = [System.Collections.Generic.HashSet[string]]::new()
$violations = [System.Collections.Generic.List[string]]::new()
$javaRoot = Join-Path $repoRoot "src\main\java"
$javaFiles = Get-ChildItem -LiteralPath $javaRoot -Recurse -File -Filter "*.java" | Sort-Object FullName

foreach ($sourceFile in $javaFiles) {
    $raw = Get-Content -LiteralPath $sourceFile.FullName -Raw -Encoding UTF8
    $packageMatch = [regex]::Match(
        $raw,
        '(?m)^\s*package\s+(com\.miningdim(?:\.[A-Za-z_][A-Za-z0-9_]*)*);'
    )
    if (-not $packageMatch.Success) {
        $violations.Add("$($sourceFile.FullName): missing com.miningdim package declaration")
        continue
    }
    $sourcePackage = $packageMatch.Groups[1].Value
    $sourceModule = Get-ModuleOwner -QualifiedName $sourcePackage
    if ($null -eq $sourceModule) {
        $violations.Add("$($sourceFile.FullName): package $sourcePackage has no module owner")
        continue
    }

    $code = [regex]::Replace($raw, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $code = [regex]::Replace($code, '//.*$', '', [System.Text.RegularExpressions.RegexOptions]::Multiline)
    $targetModules = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($reference in [regex]::Matches($code, 'com\.miningdim(?:\.[A-Za-z_][A-Za-z0-9_]*)+')) {
        $targetModule = Get-ModuleOwner -QualifiedName $reference.Value
        if ($null -ne $targetModule -and $targetModule -ne $sourceModule) {
            [void]$targetModules.Add($targetModule)
        }
    }

    $declaredDependencies = @($modulesById[$sourceModule].dependencies | ForEach-Object { [string]$_ })
    foreach ($targetModule in $targetModules) {
        if ($declaredDependencies -contains $targetModule) {
            continue
        }
        $pair = "$sourceModule->$targetModule"
        if ($allowedExceptions.Contains($pair)) {
            [void]$observedExceptions.Add($pair)
        } else {
            $relative = $sourceFile.FullName.Substring($repoRoot.Length + 1)
            $violations.Add("${relative}: $sourceModule references undeclared module $targetModule")
        }
    }
}

foreach ($pair in $allowedExceptions) {
    if (-not $observedExceptions.Contains($pair)) {
        $violations.Add("boundary exception $pair is stale; remove it from module-registry.json")
    }
}

if ($violations.Count -gt 0) {
    throw "WOK module boundary violations:`n - $($violations -join "`n - ")"
}

Write-Output (
    "Verified WOK module layout: {0} modules, {1} Java files, {2} tracked boundary exceptions, no new violations" -f
    $modulesById.Count, $javaFiles.Count, $observedExceptions.Count
)
