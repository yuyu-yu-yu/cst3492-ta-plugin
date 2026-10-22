package edu.tongji.cst3492.ta.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pre-processes course materials (PDF slides, Markdown notes, plain text) into searchable
 * {@link KnowledgeChunk} instances with exact document name and page number/scope metadata.
 */
public final class CourseDocumentParser {

    public static final int DEFAULT_MAX_CHARS_PER_CHUNK = 750;
    public static final int DEFAULT_OVERLAP_CHARS = 120;

    private final int maxCharsPerChunk;
    private final int overlapChars;
    private final Tika tika = new Tika();

    public CourseDocumentParser() {
        this(DEFAULT_MAX_CHARS_PER_CHUNK, DEFAULT_OVERLAP_CHARS);
    }

    public CourseDocumentParser(int maxCharsPerChunk, int overlapChars) {
        if (maxCharsPerChunk < 100) {
            throw new IllegalArgumentException("maxCharsPerChunk must be >= 100");
        }
        if (overlapChars < 0 || overlapChars >= maxCharsPerChunk) {
            throw new IllegalArgumentException("overlapChars must be between 0 and maxCharsPerChunk");
        }
        this.maxCharsPerChunk = maxCharsPerChunk;
        this.overlapChars = overlapChars;
    }

    public List<KnowledgeChunk> parseFile(Path path) throws IOException {
        File file = path.toFile();
        String fileName = file.getName();
        String detectedType = tika.detect(file);
        if ("application/pdf".equalsIgnoreCase(detectedType) || fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            byte[] pdfBytes = Files.readAllBytes(path);
            return parsePdfBytes(fileName, pdfBytes);
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return parsePlainTextDocument(fileName, text);
    }

    public List<KnowledgeChunk> parsePdfStream(String documentName, InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        return parsePdfBytes(documentName, bytes);
    }

    public List<KnowledgeChunk> parsePdfBytes(String documentName, byte[] pdfBytes) throws IOException {
        List<PageContent> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int totalPages = document.getNumberOfPages();
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String rawPageText = normalizeWhitespace(stripper.getText(document));
                if (!rawPageText.isBlank()) {
                    pages.add(new PageContent(page, extractHeading(rawPageText, page), rawPageText));
                }
            }
        }
        return chunkPages(documentName, pages);
    }

    public List<KnowledgeChunk> parsePlainTextDocument(String documentName, String content) {
        String normalized = normalizeWhitespace(content);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<PageContent> pages = new ArrayList<>();
        // Check if text has explicit page markers or markdown headings
        String[] sections = normalized.split("(?m)(?=^#{1,3}\\s+)");
        int virtualPage = 1;
        for (String section : sections) {
            String trimmed = section.trim();
            if (!trimmed.isBlank()) {
                pages.add(new PageContent(virtualPage, extractHeading(trimmed, virtualPage), trimmed));
                virtualPage++;
            }
        }
        return chunkPages(documentName, pages);
    }

    public List<KnowledgeChunk> chunkPages(String documentName, List<PageContent> pages) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        String docPrefix = buildDocPrefix(documentName);
        int seq = 1;

        for (PageContent page : pages) {
            String text = page.text().trim();
            if (text.isBlank()) {
                continue;
            }
            if (text.length() <= maxCharsPerChunk) {
                String id = docPrefix + "-p" + page.pageNumber() + "-" + seq++;
                chunks.add(new KnowledgeChunk(
                        id,
                        documentName,
                        page.pageNumber(),
                        page.pageNumber(),
                        page.heading(),
                        text
                ));
            } else {
                List<String> slices = splitWithOverlap(text, maxCharsPerChunk, overlapChars);
                for (String slice : slices) {
                    if (!slice.isBlank()) {
                        String id = docPrefix + "-p" + page.pageNumber() + "-" + seq++;
                        chunks.add(new KnowledgeChunk(
                                id,
                                documentName,
                                page.pageNumber(),
                                page.pageNumber(),
                                page.heading(),
                                slice
                        ));
                    }
                }
            }
        }
        return List.copyOf(chunks);
    }

    static List<String> splitWithOverlap(String text, int maxChars, int overlap) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = Math.min(length, start + maxChars);
            if (end < length) {
                int breakPos = findNaturalBreak(text, start + (maxChars / 2), end);
                if (breakPos > start) {
                    end = breakPos;
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isBlank()) {
                result.add(piece);
            }
            if (end >= length) {
                break;
            }
            int nextStart = Math.max(start + 1, end - overlap);
            // Align next start to a whitespace boundary if possible
            while (nextStart < end && !Character.isWhitespace(text.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart < end ? nextStart + 1 : end;
        }
        return result;
    }

    private static int findNaturalBreak(String text, int minPos, int maxPos) {
        for (int i = maxPos; i > minPos; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '.' || c == ';' || c == '!') {
                return i;
            }
        }
        for (int i = maxPos; i > minPos; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return maxPos;
    }

    private static String extractHeading(String pageText, int pageNumber) {
        String[] lines = pageText.split("\\R");
        for (String line : lines) {
            String cleaned = line.replaceAll("^[#*\\-\\s]+", "").trim();
            if (cleaned.length() >= 3 && cleaned.length() <= 90) {
                return cleaned;
            }
        }
        return "Slide " + pageNumber;
    }

    private static String normalizeWhitespace(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String buildDocPrefix(String documentName) {
        String stem = documentName.replaceFirst("\\.[^.]+$", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (stem.isBlank()) {
            stem = "doc";
        } else if (stem.length() > 14) {
            stem = stem.substring(0, 14).replaceAll("-+$", "");
        }
        String hashSuffix = String.format("%04x", documentName.hashCode() & 0xFFFF);
        return stem + "-" + hashSuffix;
    }

    public record PageContent(int pageNumber, String heading, String text) {}
}
