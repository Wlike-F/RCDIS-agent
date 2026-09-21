package com.rcdis.agent.infrastructure.document;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Extracts plain text from uploaded documents so the Agent can reason over them.
 *
 * <p>Supported: txt/md (native), pdf (PDFBox), docx (POI XWPF), pptx (POI XSLF). Images return
 * {@code null} (no OCR yet). Legacy .doc/.ppt are rejected upstream. Extraction failures return
 * {@code null} rather than throwing, so an upload still succeeds and is marked FAILED.</p>
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    public String extract(Path path, String kind) {
        try {
            return switch (kind == null ? "" : kind) {
                case "TEXT" -> extractText(path);
                case "PDF" -> extractPdf(path);
                case "DOCX" -> extractDocx(path);
                case "PPTX" -> extractPptx(path);
                default -> null;
            };
        } catch (Exception exception) {
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("path", path.getFileName().toString())
                    .addKeyValue("kind", kind)
                    .log("Document text extraction failed");
            return null;
        }
    }

    private String extractText(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String extractPdf(Path path) throws Exception {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractDocx(Path path) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(in)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String extractPptx(Path path) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(path);
             XMLSlideShow ppt = new XMLSlideShow(in)) {
            for (XSLFSlide slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append('\n');
                        }
                    }
                }
            }
        }
        return sb.toString();
    }
}
