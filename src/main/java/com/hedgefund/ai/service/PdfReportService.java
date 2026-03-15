package com.hedgefund.ai.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts an LLM-generated Markdown trading report into a professionally
 * styled, downloadable PDF document using OpenPDF.
 */
@Service
public class PdfReportService {

    private static final Logger log = Logger.getLogger(PdfReportService.class.getName());

    // ── Color palette ──
    private static final Color BRAND_DARK    = new Color(24, 32, 44);
    private static final Color BRAND_ACCENT  = new Color(0, 122, 255);
    private static final Color HEADER_BG     = new Color(34, 45, 65);
    private static final Color TABLE_HEADER  = new Color(44, 62, 92);
    private static final Color TABLE_ALT_ROW = new Color(240, 244, 250);
    private static final Color TEXT_MUTED     = new Color(100, 110, 130);
    private static final Color BORDER_LIGHT  = new Color(200, 210, 225);
    private static final Color SUCCESS_GREEN = new Color(16, 150, 72);
    private static final Color DANGER_RED    = new Color(210, 43, 43);

    // ── Fonts ──
    private static final Font FONT_TITLE      = new Font(Font.HELVETICA, 22, Font.BOLD, Color.WHITE);
    private static final Font FONT_H2         = new Font(Font.HELVETICA, 15, Font.BOLD, BRAND_DARK);
    private static final Font FONT_H3         = new Font(Font.HELVETICA, 13, Font.BOLD, BRAND_ACCENT);
    private static final Font FONT_BODY       = new Font(Font.HELVETICA, 10, Font.NORMAL, BRAND_DARK);
    private static final Font FONT_BODY_BOLD  = new Font(Font.HELVETICA, 10, Font.BOLD, BRAND_DARK);
    private static final Font FONT_SMALL      = new Font(Font.HELVETICA, 8, Font.NORMAL, TEXT_MUTED);
    private static final Font FONT_TABLE_HEAD = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
    private static final Font FONT_TABLE_CELL = new Font(Font.HELVETICA, 9, Font.NORMAL, BRAND_DARK);
    private static final Font FONT_QUOTE      = new Font(Font.HELVETICA, 10, Font.ITALIC, new Color(80, 90, 110));
    private static final Font FONT_BULLISH    = new Font(Font.HELVETICA, 10, Font.BOLD, SUCCESS_GREEN);
    private static final Font FONT_BEARISH    = new Font(Font.HELVETICA, 10, Font.BOLD, DANGER_RED);

    /**
     * Generate a PDF byte array from a Markdown report string.
     */
    public byte[] generatePdf(String markdownReport, List<String> tickers) throws IOException {
        // Clean up escaped newlines if the markdown was stored as a single-line JSON string
        String md = markdownReport.replace("\\n", "\n");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 50, 50);

        try {
            PdfWriter writer = PdfWriter.getInstance(doc, baos);
            writer.setPageEvent(new FooterPageEvent(tickers));
            doc.open();

            // ── Title banner ──
            addTitleBanner(doc);
            doc.add(new Paragraph(" ", FONT_SMALL)); // spacer

            // ── Parse markdown line by line ──
            String[] lines = md.split("\n");
            List<String[]> pendingTable = new ArrayList<>();
            boolean inTable = false;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();

                // Skip empty lines
                if (line.isEmpty()) {
                    if (inTable) {
                        flushTable(doc, pendingTable);
                        pendingTable.clear();
                        inTable = false;
                    }
                    doc.add(Chunk.NEWLINE);
                    continue;
                }

                // Horizontal rule
                if (line.equals("---") || line.equals("***") || line.equals("___")) {
                    if (inTable) {
                        flushTable(doc, pendingTable);
                        pendingTable.clear();
                        inTable = false;
                    }
                    addHorizontalRule(doc);
                    continue;
                }

                // Table row
                if (line.startsWith("|") && line.endsWith("|")) {
                    // Skip separator rows like |---|---|
                    if (line.matches("\\|[\\s\\-:]+\\|.*")) {
                        continue;
                    }
                    String[] cells = parseTableRow(line);
                    if (cells.length > 0) {
                        pendingTable.add(cells);
                        inTable = true;
                    }
                    continue;
                }

                // If we were in a table and hit a non-table line, flush
                if (inTable) {
                    flushTable(doc, pendingTable);
                    pendingTable.clear();
                    inTable = false;
                }

                // H1 — main title (skip it, we already have the banner)
                if (line.startsWith("# ")) {
                    continue;
                }

                // H2
                if (line.startsWith("## ")) {
                    String text = stripMarkdown(line.substring(3));
                    doc.add(new Paragraph(" ", FONT_SMALL));
                    Paragraph h2 = new Paragraph(text, FONT_H2);
                    h2.setSpacingBefore(10);
                    h2.setSpacingAfter(6);
                    doc.add(h2);
                    addThinRule(doc);
                    continue;
                }

                // H3
                if (line.startsWith("### ")) {
                    String text = stripMarkdown(line.substring(4));
                    Paragraph h3 = new Paragraph(text, FONT_H3);
                    h3.setSpacingBefore(8);
                    h3.setSpacingAfter(4);
                    doc.add(h3);
                    continue;
                }

                // Blockquote
                if (line.startsWith("> ")) {
                    String text = stripMarkdown(line.substring(2));
                    addBlockquote(doc, text);
                    continue;
                }

                // Bullet point
                if (line.startsWith("- ") || line.startsWith("* ")) {
                    String text = line.substring(2);
                    addBulletPoint(doc, text);
                    continue;
                }

                // Regular paragraph
                Paragraph para = buildRichParagraph(line);
                para.setSpacingAfter(3);
                doc.add(para);
            }

            // Flush any remaining table
            if (inTable && !pendingTable.isEmpty()) {
                flushTable(doc, pendingTable);
            }

        } catch (DocumentException e) {
            log.log(Level.SEVERE, "Error generating PDF report", e);
            throw new IOException("Failed to generate PDF", e);
        } finally {
            doc.close();
        }

        return baos.toByteArray();
    }

    // ════════════════════════════════════════════════════
    //  Title Banner
    // ════════════════════════════════════════════════════

    private void addTitleBanner(Document doc) throws DocumentException {
        PdfPTable banner = new PdfPTable(1);
        banner.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(HEADER_BG);
        cell.setPadding(20);
        cell.setBorder(0);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Paragraph title = new Paragraph("AI HEDGE FUND", FONT_TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(title);

        Font subtitleFont = new Font(Font.HELVETICA, 11, Font.NORMAL, new Color(170, 185, 210));
        Paragraph subtitle = new Paragraph("Trading Report", subtitleFont);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(subtitle);

        banner.addCell(cell);
        doc.add(banner);
    }

    // ════════════════════════════════════════════════════
    //  Table rendering
    // ════════════════════════════════════════════════════

    private void flushTable(Document doc, List<String[]> rows) throws DocumentException {
        if (rows.isEmpty()) return;

        int cols = rows.get(0).length;
        PdfPTable table = new PdfPTable(cols);
        table.setWidthPercentage(100);
        table.setSpacingBefore(6);
        table.setSpacingAfter(6);

        // Header row
        String[] header = rows.get(0);
        for (String h : header) {
            PdfPCell cell = new PdfPCell(new Phrase(stripMarkdown(h), FONT_TABLE_HEAD));
            cell.setBackgroundColor(TABLE_HEADER);
            cell.setPadding(6);
            cell.setBorderColor(BORDER_LIGHT);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }

        // Data rows
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            Color bg = (r % 2 == 0) ? TABLE_ALT_ROW : Color.WHITE;
            for (int c = 0; c < cols; c++) {
                String val = c < row.length ? row[c] : "";
                Font cellFont = chooseCellFont(val);
                PdfPCell cell = new PdfPCell(new Phrase(stripMarkdown(val), cellFont));
                cell.setBackgroundColor(bg);
                cell.setPadding(5);
                cell.setBorderColor(BORDER_LIGHT);
                // Right-align numeric-looking cells
                if (val.matches(".*\\d+\\.\\d+.*") || val.matches(".*\\d+%.*")) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }

        doc.add(table);
    }

    private Font chooseCellFont(String val) {
        String lower = val.toLowerCase().trim();
        if (lower.contains("bullish") || lower.equals("long") || lower.equals("buy")) return FONT_BULLISH;
        if (lower.contains("bearish") || lower.equals("short") || lower.equals("sell")) return FONT_BEARISH;
        return FONT_TABLE_CELL;
    }

    private String[] parseTableRow(String line) {
        String inner = line.substring(1, line.length() - 1); // strip leading/trailing |
        String[] parts = inner.split("\\|");
        String[] trimmed = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            trimmed[i] = parts[i].trim();
        }
        return trimmed;
    }

    // ════════════════════════════════════════════════════
    //  Rich text (bold / italic inline)
    // ════════════════════════════════════════════════════

    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*(.+?)\\*(?!\\*)");

    private Paragraph buildRichParagraph(String line) {
        String text = stripEmoji(line);
        Paragraph para = new Paragraph();
        para.setLeading(14);

        // Split on bold markers first
        Matcher boldMatcher = BOLD_PATTERN.matcher(text);
        int lastEnd = 0;
        while (boldMatcher.find()) {
            if (boldMatcher.start() > lastEnd) {
                para.add(new Chunk(text.substring(lastEnd, boldMatcher.start()), FONT_BODY));
            }
            para.add(new Chunk(boldMatcher.group(1), FONT_BODY_BOLD));
            lastEnd = boldMatcher.end();
        }
        if (lastEnd < text.length()) {
            para.add(new Chunk(text.substring(lastEnd), FONT_BODY));
        }
        return para;
    }

    // ════════════════════════════════════════════════════
    //  Bullet points
    // ════════════════════════════════════════════════════

    private void addBulletPoint(Document doc, String text) throws DocumentException {
        Paragraph bullet = buildRichParagraph("  •  " + text);
        bullet.setIndentationLeft(15);
        bullet.setSpacingAfter(2);
        doc.add(bullet);
    }

    // ════════════════════════════════════════════════════
    //  Blockquote
    // ════════════════════════════════════════════════════

    private void addBlockquote(Document doc, String text) throws DocumentException {
        PdfPTable bq = new PdfPTable(new float[]{3f, 97f});
        bq.setWidthPercentage(95);
        bq.setSpacingBefore(4);
        bq.setSpacingAfter(4);

        // Accent bar
        PdfPCell bar = new PdfPCell();
        bar.setBackgroundColor(BRAND_ACCENT);
        bar.setBorder(0);
        bar.setFixedHeight(0);
        bq.addCell(bar);

        // Quote text
        PdfPCell content = new PdfPCell(new Phrase(stripMarkdown(text), FONT_QUOTE));
        content.setBorder(0);
        content.setPaddingLeft(8);
        content.setBackgroundColor(new Color(245, 248, 255));
        bq.addCell(content);

        doc.add(bq);
    }

    // ════════════════════════════════════════════════════
    //  Horizontal rules
    // ════════════════════════════════════════════════════

    private void addHorizontalRule(Document doc) throws DocumentException {
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        rule.setSpacingBefore(6);
        rule.setSpacingAfter(6);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(PdfPCell.BOTTOM);
        cell.setBorderColor(BORDER_LIGHT);
        cell.setBorderWidth(1.2f);
        cell.setFixedHeight(1);
        rule.addCell(cell);
        doc.add(rule);
    }

    private void addThinRule(Document doc) throws DocumentException {
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        rule.setSpacingAfter(4);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(PdfPCell.BOTTOM);
        cell.setBorderColor(BRAND_ACCENT);
        cell.setBorderWidth(0.8f);
        cell.setFixedHeight(1);
        rule.addCell(cell);
        doc.add(rule);
    }

    // ════════════════════════════════════════════════════
    //  Markdown stripping utilities
    // ════════════════════════════════════════════════════

    private String stripMarkdown(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")  // bold
                .replaceAll("\\*(.+?)\\*", "$1")          // italic
                .replaceAll("_(.+?)_", "$1")              // underscore italic
                .replaceAll("`(.+?)`", "$1")              // inline code
                .trim();
    }

    private String stripEmoji(String text) {
        // Keep text but strip common emoji codepoints that OpenPDF can't render
        return text.replaceAll("[\\p{So}\\p{Cn}]", "").trim();
    }

    // ════════════════════════════════════════════════════
    //  Footer with page numbers
    // ════════════════════════════════════════════════════

    private static class FooterPageEvent extends PdfPageEventHelper {
        private final List<String> tickers;

        FooterPageEvent(List<String> tickers) {
            this.tickers = tickers;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Font footerFont = new Font(Font.HELVETICA, 7, Font.NORMAL, TEXT_MUTED);

            // Left: branding
            Phrase left = new Phrase("AI Hedge Fund  |  " + String.join(", ", tickers), footerFont);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, left,
                    document.left(), document.bottom() - 20, 0);

            // Right: page number
            Phrase right = new Phrase("Page " + writer.getPageNumber(), footerFont);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, right,
                    document.right(), document.bottom() - 20, 0);
        }
    }
}

