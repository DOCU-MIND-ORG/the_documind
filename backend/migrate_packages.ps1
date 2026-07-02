$ErrorActionPreference = 'Stop'
$baseDir = "c:\Users\bunny\Downloads\Pluto\backend\src\main\java\com\accenture\intern\docmind\aiservices"
$mapping = [ordered]@{
    "ContextBuilderService.java" = "context"
    "CitationService.java" = "context"
    "SuggestedQuestionsService.java" = "context"
    "RoutingService.java" = "understanding"
    "Intent.java" = "understanding"
    "Scope.java" = "understanding"
    "RetrievalPlan.java" = "understanding"
    "RetrievalStrategy.java" = "understanding"
    "LlmRoutingResponse.java" = "understanding"
    "ComparisonTarget.java" = "understanding"
    "RetrievalOrchestrator.java" = "retrieval"
    "HybridRetrievalService.java" = "retrieval"
    "VectorStoreService.java" = "retrieval"
    "RerankService.java" = "retrieval"
    "RetrievalQuality.java" = "retrieval"
    "IntegratedPineconeVectorStore.java" = "retrieval"
    "MessagesPineconeVectorStore.java" = "retrieval"
    "MemoryGatingService.java" = "memory"
    "EmbeddingService.java" = "embedding"
    "DocumentParserService.java" = "embedding"
    "ImageVisionService.java" = "vision"
    "ImageVisionResponse.java" = "vision"
    "ModelFactory.java" = "model"
    "ChatService.java" = "chat"
}

foreach ($entry in $mapping.GetEnumerator()) {
    $file = $entry.Name
    $subPkg = $entry.Value
    
    if (Test-Path "$baseDir\$file") {
        $targetDir = "$baseDir\$subPkg"
        if (!(Test-Path $targetDir)) { New-Item -ItemType Directory -Force -Path $targetDir | Out-Null }
        
        Move-Item -Path "$baseDir\$file" -Destination "$targetDir\$file" -Force
        
        $content = Get-Content "$targetDir\$file" -Raw
        $content = $content -replace 'package com\.accenture\.intern\.docmind\.aiservices;', "package com.accenture.intern.docmind.aiservices.$subPkg;"
        Set-Content -Path "$targetDir\$file" -Value $content
    }
}

$srcDir = "c:\Users\bunny\Downloads\Pluto\backend\src\main\java\com\accenture\intern\docmind"
$files = Get-ChildItem -Path $srcDir -Recurse -Filter *.java

foreach ($f in $files) {
    $content = Get-Content $f.FullName -Raw
    $original = $content
    
    foreach ($entry in $mapping.GetEnumerator()) {
        $className = $entry.Name.Replace('.java', '')
        $subPkg = $entry.Value
        
        $content = $content -replace "import com\.accenture\.intern\.docmind\.aiservices\.$className;", "import com.accenture.intern.docmind.aiservices.$subPkg.$className;"
    }
    
    if ($content -cne $original) {
        Set-Content -Path $f.FullName -Value $content
    }
}
Write-Host "Migration script completed."
