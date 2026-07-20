package com.accenture.intern.docmind.aiservices.embedding;

import com.accenture.intern.docmind.dto.context.ChunkType;
import com.accenture.intern.docmind.dto.context.SemanticChunk;
import com.accenture.intern.docmind.dto.context.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WikipediaIngestionService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WikipediaIngestionService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.objectMapper = objectMapper;
    }

    public List<SemanticChunk> fetchAndParse(String pageTitle) {
        String normalizedTitle = pageTitle.replace(" ", "_");
        
        List<String> categories = fetchCategories(normalizedTitle);
        String html = fetchHtml(normalizedTitle);

        if (html == null || html.isBlank()) {
            throw new RuntimeException("Could not fetch HTML for Wikipedia page: " + pageTitle);
        }

        return parseHtmlToSemanticChunks(html, pageTitle, categories);
    }

    private List<String> fetchCategories(String pageTitle) {
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("en.wikipedia.org")
                            .path("/w/api.php")
                            .queryParam("action", "query")
                            .queryParam("format", "json")
                            .queryParam("prop", "categories")
                            .queryParam("cllimit", "50")
                            .queryParam("titles", pageTitle)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(json);
            JsonNode queryNode = root.path("query");
            if (!queryNode.isMissingNode()) {
                JsonNode pages = queryNode.path("pages");
                if (!pages.isMissingNode() && pages.elements().hasNext()) {
                    JsonNode pageNode = pages.elements().next();
                    JsonNode categoriesNode = pageNode.path("categories");
                    if (categoriesNode.isArray()) {
                        List<String> cats = new ArrayList<>();
                        for (JsonNode cat : categoriesNode) {
                            String title = cat.path("title").asText("");
                            if (title.startsWith("Category:")) {
                            title = title.substring(9);
                        }
                        cats.add(title);
                    }
                    return cats;
                }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch categories for Wikipedia page '{}'", pageTitle, e);
        }
        return new ArrayList<>();
    }

    private String fetchHtml(String pageTitle) {
        try {
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("en.wikipedia.org")
                            .path("/api/rest_v1/page/html/" + pageTitle)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch HTML for Wikipedia page '{}'", pageTitle, e);
            return null;
        }
    }

    private List<SemanticChunk> parseHtmlToSemanticChunks(String html, String pageTitle, List<String> categories) {
        List<SemanticChunk> chunks = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        
        Elements elements = doc.select("h1, h2, h3, h4, p, ul, ol, table.infobox, table.wikitable, figure, div.thumb");
        
        String currentH1 = pageTitle;
        String currentH2 = null;
        String currentH3 = null;
        String currentH4 = null;
        
        boolean hasSeenH2 = false;
        int chunkOrder = 1;
        int pCount = 1;
        int listCount = 1;
        int imgCount = 1;
        int tableCount = 1;
        int infoCount = 1;

        Map<String, Object> baseMetadata = new HashMap<>();
        baseMetadata.put("categories", categories);

        for (Element element : elements) {
            // Skip elements that are nested inside our other block-level elements to avoid duplication
            if (element.parents().stream().anyMatch(p -> p.is("table.infobox, table.wikitable, figure, div.thumb"))) {
                continue;
            }
            
            String tagName = element.tagName().toLowerCase();

            if (tagName.equals("h1")) {
                currentH1 = element.text();
            } else if (tagName.equals("h2")) {
                currentH2 = element.text();
                currentH3 = null;
                currentH4 = null;
                hasSeenH2 = true;
            } else if (tagName.equals("h3")) {
                currentH3 = element.text();
                currentH4 = null;
            } else if (tagName.equals("h4")) {
                currentH4 = element.text();
            } else if (tagName.equals("p") || tagName.equals("ul") || tagName.equals("ol")) {
                String text;
                if (tagName.equals("ul") || tagName.equals("ol")) {
                    StringBuilder listText = new StringBuilder();
                    for (Element li : element.select("li")) {
                        listText.append("- ").append(li.text().trim()).append("\n");
                    }
                    text = listText.toString().trim();
                } else {
                    text = element.text().trim();
                }
                
                if (text.isEmpty() || text.length() < 20) continue; // Skip empty or tiny paragraphs/lists

                ChunkType type = hasSeenH2 ? ChunkType.PARAGRAPH : ChunkType.LEAD_SECTION;
                String suffix = tagName.equals("p") ? (":p" + pCount++) : (":list" + listCount++);
                String semanticId = pageTitle.replace(" ", "_") + (type == ChunkType.LEAD_SECTION ? ":summary_" : suffix);
                
                chunks.add(SemanticChunk.builder()
                        .semanticId(semanticId)
                        .sourceType(SourceType.WIKIPEDIA)
                        .type(type)
                        .text(text)
                        .sectionPath(buildSectionPath(currentH2, currentH3, currentH4))
                        .order(chunkOrder++)
                        .metadata(new HashMap<>(baseMetadata))
                        .build());

            } else if (tagName.equals("table")) {
                boolean isInfobox = element.hasClass("infobox");
                boolean isWikitable = element.hasClass("wikitable");
                
                if (isInfobox || isWikitable) {
                    String tableData = isInfobox ? parseInfobox(element) : parseWikiTable(element);
                    if (tableData.isBlank()) continue;

                    ChunkType type = isInfobox ? ChunkType.INFOBOX : ChunkType.WIKITABLE;
                    String semanticId = pageTitle.replace(" ", "_") + (isInfobox ? ":info" + infoCount++ : ":table" + tableCount++);

                    chunks.add(SemanticChunk.builder()
                            .semanticId(semanticId)
                            .sourceType(SourceType.WIKIPEDIA)
                            .type(type)
                            .text(tableData)
                            .sectionPath(buildSectionPath(currentH2, currentH3, currentH4))
                            .order(chunkOrder++)
                            .metadata(new HashMap<>(baseMetadata))
                            .build());
                }
            } else if (tagName.equals("figure") || element.hasClass("thumb")) {
                Element img = element.selectFirst("img");
                Element captionEl = element.selectFirst("figcaption, .thumbcaption");
                
                if (img != null) {
                    String src = img.attr("src");
                    if (src.startsWith("//")) src = "https:" + src;
                    
                    // Wikipedia thumbnails often look like:
                    // https://upload.wikimedia.org/wikipedia/commons/thumb/a/a0/File.jpg/220px-File.jpg
                    // We want to extract the original image URL for the 'imageUrl' field.
                    String originalUrl = src;
                    if (src.contains("/thumb/")) {
                        int thumbIndex = src.indexOf("/thumb/");
                        String basePath = src.substring(0, thumbIndex) + "/";
                        String rest = src.substring(thumbIndex + 7); // after "/thumb/"
                        int lastSlashIndex = rest.lastIndexOf("/");
                        if (lastSlashIndex != -1) {
                            String originalPath = rest.substring(0, lastSlashIndex);
                            originalUrl = basePath + originalPath;
                        }
                    }
                    
                    String alt = img.attr("alt");
                    String caption = captionEl != null ? captionEl.text() : alt;
                    
                    if (caption == null || caption.isBlank()) caption = "Image without caption";

                    Map<String, Object> imgMetadata = new HashMap<>(baseMetadata);
                    imgMetadata.put("imageUrl", originalUrl);
                    imgMetadata.put("thumbnailUrl", src);
                    imgMetadata.put("altText", alt);
                    imgMetadata.put("caption", caption);
                    imgMetadata.put("figureIndex", imgCount);

                    String semanticId = pageTitle.replace(" ", "_") + ":img" + imgCount++;

                    chunks.add(SemanticChunk.builder()
                            .semanticId(semanticId)
                            .sourceType(SourceType.WIKIPEDIA)
                            .type(ChunkType.IMAGE)
                            .text("Image Caption: " + caption)
                            .sectionPath(buildSectionPath(currentH2, currentH3, currentH4))
                            .order(chunkOrder++)
                            .metadata(imgMetadata)
                            .build());
                }
            }
        }
        
        return chunks;
    }

    private String buildSectionPath(String h2, String h3, String h4) {
        List<String> path = new ArrayList<>();
        if (h2 != null) path.add(h2);
        if (h3 != null) path.add(h3);
        if (h4 != null) path.add(h4);
        return path.isEmpty() ? "Lead" : String.join(" > ", path);
    }

    private String parseWikiTable(Element table) {
        StringBuilder md = new StringBuilder();
        Elements rows = table.select("tr");
        boolean headerProcessed = false;
        for (Element row : rows) {
            Elements cells = row.select("th, td");
            if (cells.isEmpty()) continue;
            
            md.append("|");
            for (Element cell : cells) {
                md.append(" ").append(cell.text().replace("|", "\\|")).append(" |");
            }
            md.append("\n");
            
            if (!headerProcessed) {
                md.append("|");
                for (int i = 0; i < cells.size(); i++) {
                    md.append("---|");
                }
                md.append("\n");
                headerProcessed = true;
            }
        }
        return md.toString();
    }

    private String parseInfobox(Element table) {
        StringBuilder md = new StringBuilder();
        Elements rows = table.select("tr");
        for (Element row : rows) {
            Element th = row.selectFirst("th");
            Element td = row.selectFirst("td");
            if (th != null && td != null) {
                String key = th.text().trim();
                String value = td.text().trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    md.append("**").append(key).append("**: ").append(value).append("\n");
                }
            }
        }
        return md.toString();
    }
}
