package com.fireplatform.legacyingest.importer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Exact place-parent facts from the legacy org_all.csv export; no name-based matching. */
final class LegacyPlaceHierarchy {
    record Place(String id, String name, String parentId, String companyId) { }
    private final Map<String, Place> places;
    private final String sha256;

    private LegacyPlaceHierarchy(Map<String, Place> places, String sha256) {
        this.places = Map.copyOf(places);
        this.sha256 = sha256;
    }

    static LegacyPlaceHierarchy parse(String csv) {
        String sourceCsv=csv;
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        List<List<String>> rows = rows(csv);
        if (rows.isEmpty() || !rows.getFirst().equals(List.of("type", "id", "name", "parent_id", "company_id", "address"))) {
            throw new IllegalArgumentException("Legacy org_all.csv header is not recognized");
        }
        Map<String, Place> places = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() != 6) throw new IllegalArgumentException("Malformed legacy org_all.csv row " + (i + 1));
            if (!row.get(0).equals("place")) continue;
            String id = row.get(1).trim(), name = row.get(2), parent = row.get(3).trim(), company = row.get(4).trim();
            if (id.isEmpty() || company.isEmpty()) throw new IllegalArgumentException("Legacy place row lacks id or company at row " + (i + 1));
            Place place = new Place(id, name, parent.isEmpty() || parent.equals("0") || parent.equals("\\N") ? null : parent, company);
            if (places.putIfAbsent(id, place) != null) throw new IllegalArgumentException("Duplicate legacy place id " + id);
        }
        for (Place place : places.values()) if (place.parentId() != null && !places.containsKey(place.parentId()))
            throw new IllegalArgumentException("Legacy place " + place.id() + " references absent parent " + place.parentId());
        validateAcyclic(places);
        return new LegacyPlaceHierarchy(places, sha256(sourceCsv.getBytes(StandardCharsets.UTF_8)));
    }

    Map<String, Place> places() { return places; }
    String sha256() { return sha256; }

    boolean matchesPlace(String id, String name, String companyId) {
        Place place=places.get(id);
        return place!=null && place.name().equals(name) && place.companyId().equals(companyId);
    }

    private static void validateAcyclic(Map<String, Place> places) {
        Set<String> complete = new HashSet<>();
        for (String start : places.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && !complete.contains(current)) {
                if (!path.add(current)) throw new IllegalArgumentException("Cycle in legacy place hierarchy at " + current);
                Place place = places.get(current);
                current = place == null ? null : place.parentId();
            }
            complete.addAll(path);
        }
    }

    private static List<List<String>> rows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"' && cell.isEmpty()) quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                if (!(row.size() == 1 && row.getFirst().isEmpty())) rows.add(List.copyOf(row));
                row.clear();
            } else cell.append(ch);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quoted cell in legacy org_all.csv");
        if (!row.isEmpty() || !cell.isEmpty()) { row.add(cell.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
