package org.finos.legend.engine.nlq;

import org.finos.legend.pure.dsl.definition.PureModelBuilder;
import org.finos.legend.pure.m3.Association;
import org.finos.legend.pure.m3.Property;
import org.finos.legend.pure.m3.PureClass;

import java.util.*;

/**
 * Extracts a focused model schema from the top-K retrieved classes,
 * formatted as compact text optimized for LLM context windows.
 *
 * Target: ~2-4K tokens regardless of total model size.
 */
public class ModelSchemaExtractor {

    /**
     * Builds a focused schema string from a set of relevant class names.
     * Includes full property details for the primary classes and reduced
     * detail (name + description only) for 1-hop associated classes.
     *
     * @param classNames   The set of relevant class qualified or simple names
     * @param modelBuilder The model builder with the full model
     * @return A compact text schema suitable for LLM context
     */
    public static String extractSchema(Set<String> classNames, PureModelBuilder modelBuilder) {
        Map<String, PureClass> allClasses = modelBuilder.getAllClasses();
        Map<String, Association> allAssociations = modelBuilder.getAllAssociations();

        // Resolve class names to PureClass objects
        Map<String, PureClass> primaryClasses = new LinkedHashMap<>();
        for (String name : classNames) {
            PureClass pc = allClasses.get(name);
            if (pc == null) {
                // Try lookup by simple name
                for (PureClass candidate : allClasses.values()) {
                    if (candidate.name().equals(name)) {
                        pc = candidate;
                        break;
                    }
                }
            }
            if (pc != null) {
                primaryClasses.put(pc.qualifiedName(), pc);
            }
        }

        // Find associations between primary classes
        List<Association> relevantAssociations = new ArrayList<>();
        Set<String> primaryNames = new HashSet<>();
        for (PureClass pc : primaryClasses.values()) {
            primaryNames.add(pc.qualifiedName());
            primaryNames.add(pc.name());
        }

        for (Association assoc : allAssociations.values()) {
            String t1 = assoc.property1().targetClass();
            String t2 = assoc.property2().targetClass();
            if (matchesAny(t1, primaryNames) || matchesAny(t2, primaryNames)) {
                relevantAssociations.add(assoc);
            }
        }

        // Find 1-hop neighbors (reduced detail)
        Set<String> neighborNames = new HashSet<>();
        for (Association assoc : relevantAssociations) {
            String t1 = assoc.property1().targetClass();
            String t2 = assoc.property2().targetClass();
            if (!matchesAny(t1, primaryNames)) neighborNames.add(t1);
            if (!matchesAny(t2, primaryNames)) neighborNames.add(t2);
        }

        Map<String, PureClass> neighborClasses = new LinkedHashMap<>();
        for (String name : neighborNames) {
            PureClass pc = allClasses.get(name);
            if (pc == null) {
                for (PureClass candidate : allClasses.values()) {
                    if (candidate.name().equals(name)) {
                        pc = candidate;
                        break;
                    }
                }
            }
            if (pc != null && !primaryClasses.containsKey(pc.qualifiedName())) {
                neighborClasses.put(pc.qualifiedName(), pc);
            }
        }

        // Build the schema text
        StringBuilder sb = new StringBuilder();

        // Primary classes with full detail
        sb.append("=== Classes (full detail) ===\n\n");
        for (PureClass pc : primaryClasses.values()) {
            appendClassFull(sb, pc);
        }

        // Neighbor classes with reduced detail
        if (!neighborClasses.isEmpty()) {
            sb.append("=== Related Classes (summary) ===\n\n");
            for (PureClass pc : neighborClasses.values()) {
                appendClassSummary(sb, pc);
            }
        }

        // Associations
        if (!relevantAssociations.isEmpty()) {
            sb.append("=== Associations ===\n\n");
            for (Association assoc : relevantAssociations) {
                appendAssociation(sb, assoc);
            }
        }

        return sb.toString();
    }

    /**
     * Builds a primary-only schema for the router: full class detail for each candidate
     * plus a compact "Associations:" line per class listing connected class names.
     * No neighbor summaries or full association blocks — just what the router needs to pick.
     */
    public static String extractPrimarySchema(Set<String> classNames, PureModelBuilder modelBuilder) {
        Map<String, PureClass> allClasses = modelBuilder.getAllClasses();
        Map<String, Association> allAssociations = modelBuilder.getAllAssociations();

        // Resolve class names to PureClass objects
        Map<String, PureClass> primaryClasses = new LinkedHashMap<>();
        for (String name : classNames) {
            PureClass pc = allClasses.get(name);
            if (pc == null) {
                for (PureClass candidate : allClasses.values()) {
                    if (candidate.name().equals(name)) {
                        pc = candidate;
                        break;
                    }
                }
            }
            if (pc != null) {
                primaryClasses.put(pc.qualifiedName(), pc);
            }
        }

        Set<String> primaryNames = new HashSet<>();
        for (PureClass pc : primaryClasses.values()) {
            primaryNames.add(pc.qualifiedName());
            primaryNames.add(pc.name());
        }

        // Build per-class association target map
        Map<String, List<String>> assocTargets = new LinkedHashMap<>();
        for (PureClass pc : primaryClasses.values()) {
            assocTargets.put(pc.qualifiedName(), new ArrayList<>());
        }
        for (Association assoc : allAssociations.values()) {
            String t1 = assoc.property1().targetClass();
            String t2 = assoc.property2().targetClass();
            // If t1 is primary, it navigates to t2
            for (PureClass pc : primaryClasses.values()) {
                String qn = pc.qualifiedName();
                String sn = pc.name();
                if (t1.equals(qn) || t1.equals(sn)) {
                    assocTargets.get(qn).add(simpleName(t2));
                }
                if (t2.equals(qn) || t2.equals(sn)) {
                    assocTargets.get(qn).add(simpleName(t1));
                }
            }
        }

        // Build schema text
        StringBuilder sb = new StringBuilder();
        sb.append("=== Classes ===\n\n");
        for (PureClass pc : primaryClasses.values()) {
            appendClassFull(sb, pc);
            List<String> targets = assocTargets.get(pc.qualifiedName());
            if (targets != null && !targets.isEmpty()) {
                // Deduplicate and sort
                List<String> unique = targets.stream().distinct().sorted().toList();
                sb.append("  Associations: → ").append(String.join(", ", unique)).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String simpleName(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf("::");
        return idx >= 0 ? qualifiedName.substring(idx + 2) : qualifiedName;
    }

    // ==================== Formatting ====================

    private static void appendClassFull(StringBuilder sb, PureClass pc) {
        sb.append("Class ").append(pc.qualifiedName());

        // Stereotypes
        if (!pc.stereotypes().isEmpty()) {
            sb.append(" <<");
            for (int i = 0; i < pc.stereotypes().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pc.stereotypes().get(i).toReference());
            }
            sb.append(">>");
        }
        sb.append("\n");

        // Class-level description
        String desc = pc.getTagValue("nlq::NlqProfile", "description");
        if (desc == null) desc = pc.getTagValue("doc", "doc");
        if (desc != null) {
            sb.append("  Description: ").append(desc).append("\n");
        }

        // Synonyms
        String syn = pc.getTagValue("nlq::NlqProfile", "synonyms");
        if (syn != null) {
            sb.append("  Synonyms: ").append(syn).append("\n");
        }

        // Business domain
        String domain = pc.getTagValue("nlq::NlqProfile", "businessDomain");
        if (domain != null) {
            sb.append("  Domain: ").append(domain).append("\n");
        }

        // When to use (routing hint)
        String whenToUse = pc.getTagValue("nlq::NlqProfile", "whenToUse");
        if (whenToUse != null) {
            sb.append("  When to use: ").append(whenToUse).append("\n");
        }

        // Properties
        sb.append("  Properties:\n");
        for (Property prop : pc.allProperties()) {
            sb.append("    - ").append(prop.name())
              .append(": ").append(prop.genericType().typeName())
              .append(prop.multiplicity());

            String propDesc = prop.getTagValue("nlq::NlqProfile", "description");
            if (propDesc != null) {
                sb.append("  // ").append(propDesc);
            }

            String unit = prop.getTagValue("nlq::NlqProfile", "unit");
            if (unit != null) {
                sb.append(" [").append(unit).append("]");
            }

            String sample = prop.getTagValue("nlq::NlqProfile", "sampleValues");
            if (sample != null) {
                sb.append(" e.g. ").append(sample);
            }

            sb.append("\n");
        }
        sb.append("\n");
    }

    private static void appendClassSummary(StringBuilder sb, PureClass pc) {
        sb.append("Class ").append(pc.qualifiedName());

        String desc = pc.getTagValue("nlq::NlqProfile", "description");
        if (desc == null) desc = pc.getTagValue("doc", "doc");
        if (desc != null) {
            sb.append(" — ").append(desc);
        }

        sb.append(" (").append(pc.allProperties().size()).append(" properties)\n");
    }

    private static void appendAssociation(StringBuilder sb, Association assoc) {
        sb.append(assoc.property1().targetClass())
          .append(".").append(assoc.property2().propertyName())
          .append(" → ")
          .append(assoc.property2().targetClass())
          .append("[").append(assoc.property2().multiplicity()).append("]");

        sb.append("  |  ");

        sb.append(assoc.property2().targetClass())
          .append(".").append(assoc.property1().propertyName())
          .append(" → ")
          .append(assoc.property1().targetClass())
          .append("[").append(assoc.property1().multiplicity()).append("]");

        sb.append("\n");
    }

    /**
     * Builds a rich-but-compact schema for LLM prompts, extracting all NLQ annotations
     * (description, synonyms, sampleValues, unit, stereotype) in a structured format.
     *
     * This gives the LLM the semantic context it needs to generate accurate queries
     * on the first try, while keeping the prompt size manageable (~1-2K chars).
     *
     * Example output (per class):
     *   etf::Fund <<core>>
     *     description: An investment fund (ETF or mutual fund)
     *     synonyms: fund, etf, mutual fund, ticker
     *     properties:
     *       ticker: String
     *       aum: Float  [unit: USD millions]  [e.g. 503000]
     *       assetClass: String  [e.g. EQUITY, FIXED_INCOME, COMMODITY]
     *     → holdings: Holding[*], navRecords: NAVRecord[*]
     */
    public static String extractRichCompactSchema(Set<String> classNames, PureModelBuilder modelBuilder) {
        Map<String, PureClass> allClasses = modelBuilder.getAllClasses();
        Map<String, Association> allAssociations = modelBuilder.getAllAssociations();

        // Resolve to PureClass objects (primary + neighbors)
        Map<String, PureClass> primaryClasses = new LinkedHashMap<>();
        for (String name : classNames) {
            PureClass pc = allClasses.get(name);
            if (pc == null) for (PureClass c : allClasses.values()) if (c.name().equals(name)) { pc = c; break; }
            if (pc != null) primaryClasses.put(pc.qualifiedName(), pc);
        }

        Set<String> primaryQNames = new LinkedHashSet<>();
        for (PureClass pc : primaryClasses.values()) {
            primaryQNames.add(pc.qualifiedName());
            primaryQNames.add(pc.name());
        }

        // Build navigation map: qualifiedName → [(propName, targetClass, mult)]
        Map<String, List<String>> navMap = new LinkedHashMap<>();
        for (Association assoc : allAssociations.values()) {
            String t1 = assoc.property1().targetClass();
            String t2 = assoc.property2().targetClass();
            for (PureClass pc : primaryClasses.values()) {
                String qn = pc.qualifiedName();
                if (matchesAny(t1, primaryQNames)) {
                    navMap.computeIfAbsent(t1, k -> new ArrayList<>())
                          .add(assoc.property2().propertyName() + ": " + simpleName(t2) + "[" + assoc.property2().multiplicity() + "]");
                }
                if (matchesAny(t2, primaryQNames)) {
                    navMap.computeIfAbsent(t2, k -> new ArrayList<>())
                          .add(assoc.property1().propertyName() + ": " + simpleName(t1) + "[" + assoc.property1().multiplicity() + "]");
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (PureClass pc : primaryClasses.values()) {
            appendRichCompact(sb, pc, navMap);
        }
        return sb.toString();
    }

    private static void appendRichCompact(StringBuilder sb, PureClass pc, Map<String, List<String>> navMap) {
        // Class name + stereotype
        sb.append(pc.qualifiedName());
        if (!pc.stereotypes().isEmpty()) {
            sb.append(" <<");
            for (int i = 0; i < pc.stereotypes().size(); i++) {
                if (i > 0) sb.append(", ");
                // Extract just the stereotype name (after the last dot)
                String ref = pc.stereotypes().get(i).toReference();
                int dot = ref.lastIndexOf('.');
                sb.append(dot >= 0 ? ref.substring(dot + 1) : ref);
            }
            sb.append(">>");
        }
        sb.append("\n");

        // Description (1 line, truncated at 120 chars)
        String desc = pc.getTagValue("nlq::NlqProfile", "description");
        if (desc == null) desc = pc.getTagValue("doc", "doc");
        if (desc != null && !desc.isBlank()) {
            sb.append("  desc: ").append(desc.length() > 120 ? desc.substring(0, 120) + "…" : desc).append("\n");
        }

        // Synonyms (compact)
        String syn = pc.getTagValue("nlq::NlqProfile", "synonyms");
        if (syn != null && !syn.isBlank()) {
            sb.append("  synonyms: ").append(syn).append("\n");
        }

        // Properties with type + unit + sampleValues
        sb.append("  properties:\n");
        for (Property prop : pc.allProperties()) {
            sb.append("    ").append(prop.name()).append(": ").append(prop.genericType().typeName());

            String unit = prop.getTagValue("nlq::NlqProfile", "unit");
            if (unit != null && !unit.isBlank()) {
                sb.append("  [unit: ").append(unit).append("]");
            }
            String sample = prop.getTagValue("nlq::NlqProfile", "sampleValues");
            if (sample != null && !sample.isBlank()) {
                // Truncate samples to keep concise
                String s = sample.length() > 80 ? sample.substring(0, 80) + "…" : sample;
                sb.append("  [e.g. ").append(s).append("]");
            }
            sb.append("\n");
        }

        // Class-level unit/sample metadata (for context)
        String classUnit = pc.getTagValue("nlq::NlqProfile", "unit");
        if (classUnit != null && !classUnit.isBlank()) {
            sb.append("  units: ").append(classUnit).append("\n");
        }
        String classSample = pc.getTagValue("nlq::NlqProfile", "sampleValues");
        if (classSample != null && !classSample.isBlank()) {
            sb.append("  sampleValues: ").append(
                    classSample.length() > 120 ? classSample.substring(0, 120) + "…" : classSample).append("\n");
        }

        // Navigation (association paths)
        List<String> navs = new ArrayList<>();
        // Check by qualifiedName and by simple name
        List<String> n1 = navMap.get(pc.qualifiedName());
        List<String> n2 = navMap.get(pc.name());
        if (n1 != null) navs.addAll(n1);
        if (n2 != null) navs.addAll(n2);
        if (!navs.isEmpty()) {
            List<String> unique = navs.stream().distinct().toList();
            sb.append("  nav: ").append(String.join(", ", unique)).append("\n");
        }
        sb.append("\n");
    }

    /**
     * Builds an ultra-compact schema for LLM prompts — just class names, property
     * names/types, and associations. No metadata, no descriptions, no annotations.
     * Target output: ~300-600 chars regardless of how richly annotated the model is.
     *
     * Example output:
     *   etf::Fund: fundId(Integer), ticker(String), aum(Float), assetClass(String)
     *     → holdings: [Holding], navRecords: [NAVRecord]
     *   etf::Holding: holdingId(Integer), weight(Float), shares(Float)
     *     ← fund: Fund, ← security: Security
     */
    public static String extractMinimalSchema(Set<String> classNames, PureModelBuilder modelBuilder) {
        Map<String, PureClass> allClasses = modelBuilder.getAllClasses();
        Map<String, Association> allAssociations = modelBuilder.getAllAssociations();

        // Resolve to PureClass objects
        Map<String, PureClass> primaryClasses = new LinkedHashMap<>();
        for (String name : classNames) {
            PureClass pc = allClasses.get(name);
            if (pc == null) {
                for (PureClass candidate : allClasses.values()) {
                    if (candidate.name().equals(name)) { pc = candidate; break; }
                }
            }
            if (pc != null) primaryClasses.put(pc.qualifiedName(), pc);
        }

        // Build navigation map: class → [(propName, targetClass, mult)]
        Map<String, List<String[]>> navMap = new LinkedHashMap<>();
        for (PureClass pc : primaryClasses.values()) {
            navMap.put(pc.qualifiedName(), new ArrayList<>());
        }
        for (Association assoc : allAssociations.values()) {
            String t1 = assoc.property1().targetClass();
            String t2 = assoc.property2().targetClass();
            for (PureClass pc : primaryClasses.values()) {
                String qn = pc.qualifiedName();
                String sn = pc.name();
                // t1 can navigate to t2 via property2
                if (t1.equals(qn) || t1.equals(sn)) {
                    navMap.computeIfAbsent(qn, k -> new ArrayList<>())
                          .add(new String[]{assoc.property2().propertyName(), simpleName(t2), assoc.property2().multiplicity().toString()});
                }
                if (t2.equals(qn) || t2.equals(sn)) {
                    navMap.computeIfAbsent(qn, k -> new ArrayList<>())
                          .add(new String[]{assoc.property1().propertyName(), simpleName(t1), assoc.property1().multiplicity().toString()});
                }
            }
        }

        // Also include 1-hop neighbors so LLM knows about navigable types
        Set<String> neighborNames = new LinkedHashSet<>();
        for (Association assoc : allAssociations.values()) {
            String t1 = assoc.property1().targetClass();
            String t2 = assoc.property2().targetClass();
            boolean t1Primary = primaryClasses.values().stream().anyMatch(pc -> pc.qualifiedName().equals(t1) || pc.name().equals(t1));
            boolean t2Primary = primaryClasses.values().stream().anyMatch(pc -> pc.qualifiedName().equals(t2) || pc.name().equals(t2));
            if (t1Primary && !t2Primary) neighborNames.add(t2);
            if (t2Primary && !t1Primary) neighborNames.add(t1);
        }
        Map<String, PureClass> neighborClasses = new LinkedHashMap<>();
        for (String name : neighborNames) {
            PureClass pc = allClasses.get(name);
            if (pc == null) for (PureClass c : allClasses.values()) if (c.name().equals(name)) { pc = c; break; }
            if (pc != null && !primaryClasses.containsKey(pc.qualifiedName())) neighborClasses.put(pc.qualifiedName(), pc);
        }

        StringBuilder sb = new StringBuilder();
        for (PureClass pc : primaryClasses.values()) {
            appendMinimal(sb, pc, navMap.getOrDefault(pc.qualifiedName(), List.of()));
        }
        if (!neighborClasses.isEmpty()) {
            sb.append("  (related: ");
            sb.append(String.join(", ", neighborClasses.values().stream().map(PureClass::qualifiedName).toList()));
            sb.append(")\n");
        }
        return sb.toString();
    }

    private static void appendMinimal(StringBuilder sb, PureClass pc, List<String[]> nav) {
        sb.append(pc.qualifiedName()).append(": ");

        // Properties — name(Type) format, max 12
        List<String> propParts = new ArrayList<>();
        int count = 0;
        for (Property prop : pc.allProperties()) {
            if (count++ >= 12) { propParts.add("..."); break; }
            propParts.add(prop.name() + "(" + prop.genericType().typeName() + ")");
        }
        sb.append(String.join(", ", propParts)).append("\n");

        // Navigation
        if (!nav.isEmpty()) {
            List<String> navParts = new ArrayList<>();
            for (String[] n : nav) {
                navParts.add(n[0] + ": " + n[1] + "[" + n[2] + "]");
            }
            sb.append("  nav: ").append(String.join(", ", navParts)).append("\n");
        }
    }

    /**
     * Extracts routing hints from model metadata for the semantic router.
     * Builds a compact string of disambiguation hints from whenToUse and exampleQuestions tags.
     * Returns empty string if no hints are found (model-agnostic).
     */
    public static String extractRoutingHints(Set<String> classNames, PureModelBuilder modelBuilder) {
        Map<String, PureClass> allClasses = modelBuilder.getAllClasses();
        StringBuilder sb = new StringBuilder();

        for (String name : classNames) {
            PureClass pc = allClasses.get(name);
            if (pc == null) {
                for (PureClass candidate : allClasses.values()) {
                    if (candidate.name().equals(name)) {
                        pc = candidate;
                        break;
                    }
                }
            }
            if (pc == null) continue;

            String whenToUse = pc.getTagValue("nlq::NlqProfile", "whenToUse");
            String examples = pc.getTagValue("nlq::NlqProfile", "exampleQuestions");

            if (whenToUse != null || examples != null) {
                sb.append(pc.name()).append(": ");
                if (whenToUse != null) {
                    sb.append(whenToUse);
                }
                if (examples != null) {
                    if (whenToUse != null) sb.append(" ");
                    sb.append("(examples: ").append(examples.replace("|", ", ")).append(")");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private static boolean matchesAny(String name, Set<String> names) {
        if (names.contains(name)) return true;
        // Try simple name extraction
        if (name.contains("::")) {
            String simple = name.substring(name.lastIndexOf("::") + 2);
            return names.contains(simple);
        }
        return false;
    }
}
