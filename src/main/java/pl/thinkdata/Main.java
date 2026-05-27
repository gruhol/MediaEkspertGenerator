package pl.thinkdata;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) throws Exception {
        String eanFile   = args.length > 0 ? args[0] : "ean.txt";
        String cnyFile   = args.length > 1 ? args[1] : "cny.txt";
        String stanyFile = args[2];
        String pubFile   = args[3];
        String outFile   = args.length > 4 ? args[4] : "output.xml";
        run(eanFile, cnyFile, stanyFile, pubFile, outFile);
    }

    public static void run(String eanFile, String cnyFile, String stanyFile, String pubFile, String outFile) throws Exception {
        System.out.println("Wczytywanie EAN: " + eanFile);
        List<String> eans = Files.readAllLines(Paths.get(eanFile), StandardCharsets.UTF_8)
                .stream()
                .map(s -> s.replaceAll("[^0-9]", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        System.out.println("Wczytywanie kodow CN: " + cnyFile);
        Map<String, String> cnCodes = loadCnCodes(cnyFile);

        System.out.println("Wczytywanie stanow: " + stanyFile);
        Map<String, Map<String, String>> stany = loadCsv(stanyFile, "KodTowaru");

        System.out.println("Wczytywanie publikacji: " + pubFile);
        Map<String, Map<String, String>> pub = loadCsv(pubFile, "KodTowaru");

        System.out.println("Generowanie XML: " + outFile);
        generateXml(eans, cnCodes, stany, pub, outFile);
    }

    // -------------------------------------------------------------------------

    private static Map<String, String> loadCnCodes(String path) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", 2);
            if (parts.length == 2) map.put(parts[0].trim(), parts[1].trim());
        }
        return map;

    }

    private static Map<String, Map<String, String>> loadCsv(String path, String keyCol) throws IOException {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) return result;
            List<String> headers = parseLine(headerLine).stream().map(String::trim).collect(Collectors.toList());
            int keyIdx = headers.indexOf(keyCol);
            if (keyIdx < 0) {
                System.err.println("Brak kolumny '" + keyCol + "' w " + path);
                return result;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> row = parseLine(line);
                if (row.size() <= keyIdx) continue;
                String key = row.get(keyIdx).trim();
                if (key.isEmpty()) continue;
                Map<String, String> rowMap = new HashMap<>();
                for (int j = 0; j < headers.size() && j < row.size(); j++) {
                    rowMap.put(headers.get(j), row.get(j));
                }
                result.put(key, rowMap);
            }
        }
        return result;
    }

    private static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ';') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
        }
        fields.add(field.toString());
        return fields;
    }

    // -------------------------------------------------------------------------

    private static void generateXml(List<String> eans,
                                    Map<String, String> cnCodes,
                                    Map<String, Map<String, String>> stany,
                                    Map<String, Map<String, String>> pub,
                                    String outFile) throws Exception {

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        int found = 0, skipped = 0;

        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            XMLStreamWriter xml = factory.createXMLStreamWriter(writer);
            xml.writeStartDocument("utf-8", "1.0");
            xml.writeCharacters("\n");
            xml.writeStartElement("offer");
            xml.writeAttribute("generated", now);
            xml.writeCharacters("\n");

            for (String ean : eans) {
                Map<String, String> p = pub.get(ean);
                if (p == null) {
                    System.out.println("  POMIN (brak w Publikacje): " + ean);
                    skipped++;
                    continue;
                }
                Map<String, String> s = stany.containsKey(ean) ? stany.get(ean) : Collections.<String, String>emptyMap();

                xml.writeCharacters("\t");
                xml.writeStartElement("article");
                xml.writeCharacters("\n");

                writeCdata(xml, "brand",    p.getOrDefault("Wydawnictwo", ""));
                writeText(xml,  "index",    p.getOrDefault("IdTowaru", ""));
                writeCdata(xml, "name",     p.getOrDefault("Nazwa", ""));
                writeText(xml,  "ean",      ean);
                writeCdata(xml, "category", p.getOrDefault("Kategoria", ""));
                writeText(xml,  "stock",    parseLowerBound(s.containsKey("Stan100") ? s.get("Stan100") : "0"));
                writeText(xml,  "net_price", s.getOrDefault("Cena netto po rabacie", ""));

                String cenaDet = p.getOrDefault("CenaDetalicznaBrutto", "");
                if (!cenaDet.isEmpty()) {
                    writeText(xml, "manufacturers_suggested_price", cenaDet);
                }

                String vat = p.getOrDefault("VAT", "").replace("%", "").trim();
                writeText(xml, "vat", vat);

                String cn = cnCodes.get(ean);
                if (cn != null && !cn.isEmpty()) writeText(xml, "cn_code", cn);

                writeText(xml, "length", mmToCm(p.getOrDefault("Szerokosc", "")));
                writeText(xml, "width",  mmToCm(p.getOrDefault("Wysokosc", "")));
                writeText(xml, "height", mmToCm(p.getOrDefault("Glebokosc", "")));
                writeText(xml, "net_weight", gToKg(p.getOrDefault("Waga", "")));
                writeText(xml, "gross_weight", gToKg(p.getOrDefault("Waga", "")));

                String urlOkladka   = p.getOrDefault("URLOkladka", "");
                String urlDodatkowe = p.getOrDefault("URLZdjeciaUzupelniajace", "");

                xml.writeCharacters("\t\t");
                xml.writeStartElement("pictures");
                xml.writeCharacters("\n");

                if (!urlOkladka.isEmpty()) writePic(xml, urlOkladka);
                if (!urlDodatkowe.isEmpty()) {
                    for (String url : urlDodatkowe.split("[,|]")) {
                        url = url.trim();
                        if (!url.isEmpty()) writePic(xml, url);
                    }
                }

                xml.writeCharacters("\t\t");
                xml.writeEndElement();
                xml.writeCharacters("\n");

                writeCdata(xml, "description", p.getOrDefault("Opis", ""));

                xml.writeCharacters("\t");
                xml.writeEndElement();
                xml.writeCharacters("\n");
                found++;
            }

            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
        }

        System.out.println("Wygenerowano " + found + " artykulow, pominieto " + skipped + ".");
    }

    private static void writeCdata(XMLStreamWriter xml, String tag, String value) throws Exception {
        xml.writeCharacters("\t\t");
        xml.writeStartElement(tag);
        xml.writeCData(value);
        xml.writeEndElement();
        xml.writeCharacters("\n");
    }

    private static void writeText(XMLStreamWriter xml, String tag, String value) throws Exception {
        xml.writeCharacters("\t\t");
        xml.writeStartElement(tag);
        xml.writeCharacters(value);
        xml.writeEndElement();
        xml.writeCharacters("\n");
    }

    private static void writePic(XMLStreamWriter xml, String url) throws Exception {
        xml.writeCharacters("\t\t\t");
        xml.writeStartElement("pic");
        xml.writeCData(url);
        xml.writeEndElement();
        xml.writeCharacters("\n");
    }

    private static String parseLowerBound(String value) {
        if (value == null || value.trim().isEmpty()) return "0";
        String v = value.trim();
        int dash = v.indexOf('-');
        if (dash > 0) return v.substring(0, dash).trim();
        return v;
    }

    private static String mmToCm(String mm) {
        if (mm == null || mm.trim().isEmpty()) return "";
        try { return String.format(Locale.US, "%.2f", Double.parseDouble(mm) / 10.0); }
        catch (NumberFormatException e) { return ""; }
    }

    private static String gToKg(String g) {
        if (g == null || g.trim().isEmpty()) return "";
        try { return String.format(Locale.US, "%.3f", Double.parseDouble(g) / 1000.0); }
        catch (NumberFormatException e) { return ""; }
    }
}