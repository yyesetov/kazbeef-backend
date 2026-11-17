package kz.kazbeef.export;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import kz.kazbeef.api.EventFilter;
import kz.kazbeef.api.EventQueries;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Выгрузка событий по текущему фильтру: Excel, CSV и недельная сводка в PDF. */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private static final int MAX_ROWS = 20_000;
    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Map<String, String> STATUS = Map.of("new", "Новое", "ongoing", "Продолжается", "closed", "Закрыто");
    private static final Map<String, String> TRUST = Map.of("confirmed", "Подтверждено", "signal", "Сигнал", "analytics", "Аналитика");

    private record Column(String title, int width, boolean numeric, Function<Map<String, Object>, Object> value) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("ID", 8, true, e -> e.get("id")),
            new Column("Дата события", 14, false, e -> e.get("eventDate")),
            new Column("Дата отчёта", 14, false, e -> e.get("reportDate")),
            new Column("Страна", 18, false, e -> e.get("country")),
            new Column("Регион", 30, false, e -> e.get("region")),
            new Column("Болезнь", 34, false, e -> e.get("disease")),
            new Column("Виды животных", 24, false, e -> e.get("species") != null ? e.get("species") : e.get("rawSpecies")),
            new Column("Статус", 14, false, e -> STATUS.getOrDefault(String.valueOf(e.get("status")), "")),
            new Column("Уровень доверия", 16, false, e -> TRUST.getOrDefault(String.valueOf(e.get("trustLevel")), "")),
            new Column("Значимо для КРС/МРС", 12, false, e -> Boolean.TRUE.equals(e.get("ruminantRelevant")) ? "да" : "нет"),
            new Column("Риск (0–100)", 10, true, e -> e.get("riskScore")),
            new Column("До границы РК, км", 12, true, e -> e.get("distanceKm")),
            new Column("Очаги", 10, true, e -> e.get("outbreaks")),
            new Column("Восприимчивых", 14, true, e -> e.get("susceptible")),
            new Column("Случаи", 10, true, e -> e.get("cases")),
            new Column("Падёж", 10, true, e -> e.get("deaths")),
            new Column("Широта", 11, true, e -> e.get("latitude")),
            new Column("Долгота", 11, true, e -> e.get("longitude")),
            new Column("Источники", 16, false, e -> e.get("sources")),
            new Column("Заголовок", 60, false, e -> e.get("title")),
            new Column("Резюме (ИИ)", 60, false, e -> e.get("aiSummary")));

    private final EventQueries queries;

    public ExportController(EventQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/events.csv")
    public ResponseEntity<byte[]> csv(EventFilter filter) {
        // BOM и точка с запятой: файл открывается в Excel двойным щелчком без искажения кириллицы
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(";", COLUMNS.stream().map(c -> quote(c.title())).toList())).append("\r\n");
        for (Map<String, Object> e : queries.list(filter, "eventDate", "desc", MAX_ROWS)) {
            sb.append(String.join(";", COLUMNS.stream().map(c -> quote(c.value().apply(e))).toList())).append("\r\n");
        }
        return file(sb.toString().getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8", "csv");
    }

    private static String quote(Object value) {
        if (value == null) return "";
        String s = value.toString();
        // значение, начинающееся с = + - @, Excel воспринял бы как формулу
        if (!(value instanceof Number) && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.matches(".*[;\"\\r\\n].*") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    @GetMapping("/events.xlsx")
    public ResponseEntity<byte[]> xlsx(EventFilter filter) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("События");
            CellStyle header = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            header.setFont(bold);
            Row head = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.size(); i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(COLUMNS.get(i).title());
                cell.setCellStyle(header);
                sheet.setColumnWidth(i, COLUMNS.get(i).width() * 256);
            }
            sheet.createFreezePane(0, 1);
            int r = 1;
            for (Map<String, Object> e : queries.list(filter, "eventDate", "desc", MAX_ROWS)) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < COLUMNS.size(); i++) {
                    Object v = COLUMNS.get(i).value().apply(e);
                    if (v == null) continue;
                    if (v instanceof Number n) row.createCell(i).setCellValue(n.doubleValue());
                    else row.createCell(i).setCellValue(v.toString());
                }
            }
            wb.write(out);
            return file(out.toByteArray(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        }
    }

    /** Сводка за последние 7 дней по дате отчёта; период из фильтра не учитывается, остальные условия сохраняются. */
    @GetMapping("/weekly.pdf")
    public ResponseEntity<byte[]> weeklyPdf(EventFilter f) throws IOException {
        LocalDate to = LocalDate.now(ALMATY);
        LocalDate from = to.minusDays(7);
        // «новое за неделю» определяется датой отчёта, поэтому берём шире и отбираем по ней
        EventFilter wide = new EventFilter(f.countries(), f.diseases(), f.groups(), f.sources(), f.trust(), f.status(),
                null, null, f.ruminantOnly(), f.q(), f.minRisk());
        List<Map<String, Object>> reported = queries.list(wide, "reportDate", "desc", MAX_ROWS).stream()
                .filter(e -> e.get("reportDate") != null && !LocalDate.parse(e.get("reportDate").toString()).isBefore(from))
                .toList();
        Map<String, Object> summary = queries.summary(wide);
        List<Map<String, Object>> top = queries.list(wide, "riskScore", "desc", 10).stream()
                .filter(e -> e.get("riskScore") != null && !"closed".equals(e.get("status"))).toList();

        BaseFont base = font("fonts/DejaVuSans.ttf");
        BaseFont baseBold = font("fonts/DejaVuSans-Bold.ttf");
        Font h1 = new Font(baseBold, 16);
        Font h2 = new Font(baseBold, 11);
        Font text = new Font(base, 9);
        Font small = new Font(base, 8, Font.NORMAL, new Color(110, 110, 110));
        Font th = new Font(baseBold, 8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("KazBeef: недельная сводка", h1));
        doc.add(new Paragraph("Период: " + RU_DATE.format(from) + " – " + RU_DATE.format(to)
                + (f.ruminantOnlyOrDefault() ? " · только события, значимые для КРС и МРС" : " · все виды животных"), small));
        doc.add(new Paragraph(" ", small));

        doc.add(new Paragraph("Сообщений за неделю: " + reported.size()
                + " · всего событий в базе по фильтру: " + summary.get("events")
                + " · активных: " + summary.get("active")
                + " · в Казахстане: " + summary.get("inKazakhstan")
                + " · в приграничной зоне: " + summary.get("nearBorder"), text));

        doc.add(spaced("Сообщения за неделю (по дате отчёта)", h2));
        if (reported.isEmpty()) {
            doc.add(new Paragraph("За неделю новых сообщений нет.", text));
        } else {
            doc.add(table(reported, th, text));
        }

        doc.add(spaced("Незакрытые события с наибольшим риском для Казахстана", h2));
        if (top.isEmpty()) {
            doc.add(new Paragraph("Незакрытых событий нет.", text));
        } else {
            doc.add(table(top, th, text));
        }
        doc.add(new Paragraph(" ", small));
        doc.add(new Paragraph("Риск — балл 0–100: вес болезни, близость к границе Казахстана, давность, статус, масштаб и динамика. "
                + "«Сигнал» — сведения из СМИ, не подтверждённые официально.", small));
        doc.close();
        return file(out.toByteArray(), MediaType.APPLICATION_PDF_VALUE, "pdf");
    }

    private static Paragraph spaced(String s, Font font) {
        Paragraph p = new Paragraph(s, font);
        p.setSpacingBefore(12);
        p.setSpacingAfter(6);
        return p;
    }

    private static PdfPTable table(List<Map<String, Object>> rows, Font th, Font td) {
        String[] titles = {"Отчёт", "Страна", "Болезнь", "Регион", "Виды", "Статус", "Риск", "Очаги", "Случаи", "Падёж", "Источники"};
        float[] widths = {9, 9, 16, 15, 10, 9, 5, 5, 6, 6, 10};
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String title : titles) {
            PdfPCell cell = new PdfPCell(new Phrase(title, th));
            cell.setBackgroundColor(new Color(236, 239, 244));
            cell.setPadding(4);
            table.addCell(cell);
        }
        for (Map<String, Object> e : rows) {
            String trust = "confirmed".equals(e.get("trustLevel")) ? "" : " (" + TRUST.getOrDefault(String.valueOf(e.get("trustLevel")), "").toLowerCase() + ")";
            Object[] values = {
                    ruDate(e.get("reportDate")), e.get("country"), e.get("disease"), e.get("region"),
                    e.get("species") != null ? e.get("species") : e.get("rawSpecies"),
                    STATUS.getOrDefault(String.valueOf(e.get("status")), ""), e.get("riskScore"),
                    e.get("outbreaks"), e.get("cases"), e.get("deaths"),
                    String.valueOf(e.get("sources")).replace(",", ", ") + trust};
            for (int i = 0; i < values.length; i++) {
                String s = values[i] == null ? "—" : values[i].toString();
                PdfPCell cell = new PdfPCell(new Phrase(s.length() > 160 ? s.substring(0, 160) + "…" : s, td));
                cell.setPadding(3);
                if (i >= 6 && i <= 9) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                table.addCell(cell);
            }
        }
        return table;
    }

    private static String ruDate(Object iso) {
        return iso == null ? null : RU_DATE.format(LocalDate.parse(iso.toString()));
    }

    private static BaseFont font(String path) throws IOException {
        byte[] bytes = new ClassPathResource(path).getContentAsByteArray();
        return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
    }

    private static ResponseEntity<byte[]> file(byte[] body, String contentType, String extension) {
        String name = "kazbeef-" + LocalDate.now(ALMATY) + "." + extension;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(body);
    }
}
