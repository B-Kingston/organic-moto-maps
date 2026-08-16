import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.Profile;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.json.Statement;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.Helper;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Dumps the Janino-generated custom weighting helper source for the motorcycle
 * profile so it can be shipped as a plain Java class in the app (ART cannot
 * load Janino-generated JVM bytecode at runtime, see AGENTS.md).
 *
 * <p>Before dumping, this verifies that the three copies of the motorcycle
 * custom model describe the same model:
 * <ol>
 *   <li>the JAR built-in <code>/com/graphhopper/custom_models/motorcycle.json</code>
 *       (what <code>custom_model_files: [motorcycle.json]</code> resolves to at
 *       import time),</li>
 *   <li>the checked-in canonical reference copy <code>tools/gh/motorcycle.json</code>,</li>
 *   <li>the <code>motorcycleProfile()</code> construction below (which must stay
 *       bit-for-bit identical to the app's GraphHopperRouter.motorcycleProfile()).</li>
 * </ol>
 * Any drift aborts with a non-zero exit code.
 *
 * <p>Run with the Janino source-debugging system properties:
 * <pre>
 *   -Dorg.codehaus.janino.source_debugging.enable=true
 *   -Dorg.codehaus.janino.source_debugging.dir=&lt;existing output dir&gt;
 * </pre>
 * and the graph-cache directory as args[0]. The dumped file is
 * JaninoCustomWeightingHelperSubclass&lt;N&gt;.java in the output dir.
 */
public class GenerateWeighting {

    private static final String BUILT_IN_MODEL = "/com/graphhopper/custom_models/motorcycle.json";
    private static final String REFERENCE_MODEL = "tools/gh/motorcycle.json";

    public static void main(String[] args) {
        String graphLocation = args.length > 0 ? args[0] : "data/graph-cache";

        // ---- canonical model sync check ------------------------------------
        CustomModel builtIn = loadBuiltInModel();
        CustomModel reference = loadReferenceModel();
        CustomModel profileModel = motorcycleProfile().getCustomModel();
        if (!sameModel(builtIn, reference))
            throw new IllegalStateException(
                    "tools/gh/motorcycle.json drifted from the JAR built-in " + BUILT_IN_MODEL + ".\n" +
                    "The import uses the built-in model (custom_model_files: [motorcycle.json] resolves to it);\n" +
                    "the checked-in reference copy must stay identical. Align the file, then re-import the graph\n" +
                    "and regenerate the helper.");
        if (!sameModel(builtIn, profileModel))
            throw new IllegalStateException(
                    "motorcycleProfile() in GenerateWeighting.java drifted from the JAR built-in " + BUILT_IN_MODEL + ".\n" +
                    "Keep it in sync with GraphHopperRouter.motorcycleProfile() and the built-in model, then\n" +
                    "re-import the graph (the stored profile version depends on it) and regenerate the helper.");
        System.out.println("OK: canonical motorcycle model in sync (JAR built-in == tools/gh/motorcycle.json == profile construction)");

        // ---- load graph and trigger Janino compilation ---------------------
        GraphHopperConfig config = new GraphHopperConfig();
        config.putObject("graph.location", graphLocation);
        config.putObject("graph.dataaccess.default_type", "MMAP");
        config.putObject("import.osm.ignored_highways", "footway,steps,corridor,bridleway");

        GraphHopper hopper = new GraphHopper();
        hopper.init(config);
        hopper.setProfiles(motorcycleProfile());
        // load() -> checkProfilesConsistency() -> createWeighting() triggers the
        // Janino compilation; with source_debugging enabled the generated source
        // is dumped to the configured directory.
        hopper.importOrLoad();
        System.out.println("OK: weighting compiled; generated source dumped to the source_debugging.dir");
    }

    private static CustomModel loadBuiltInModel() {
        InputStream is = GenerateWeighting.class.getResourceAsStream(BUILT_IN_MODEL);
        if (is == null)
            throw new IllegalStateException("Cannot find built-in model resource " + BUILT_IN_MODEL +
                    " (does the graphhopper jar in tools/gh match the pinned graphhopper-core version in gradle/libs.versions.toml?)");
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return Jackson.newObjectMapper().readValue(Helper.readJSONFileWithoutComments(reader), CustomModel.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse built-in model resource " + BUILT_IN_MODEL, e);
        }
    }

    private static CustomModel loadReferenceModel() {
        try {
            return Jackson.newObjectMapper().readValue(
                    Helper.readJSONFileWithoutComments(REFERENCE_MODEL), CustomModel.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse reference model " + REFERENCE_MODEL +
                    " (run from the repo root)", e);
        }
    }

    private static boolean sameModel(CustomModel a, CustomModel b) {
        if (!Objects.equals(a.getDistanceInfluence(), b.getDistanceInfluence())) return false;
        if (!a.getPriority().toString().equals(b.getPriority().toString())) return false;
        if (!a.getSpeed().toString().equals(b.getSpeed().toString())) return false;
        return true;
    }

    /**
     * Must stay bit-for-bit identical to GraphHopperRouter.motorcycleProfile()
     * in the app (app/src/main/java/com/organicmoto/maps/routing/GraphHopperRouter.kt):
     * the stored graph's profile version hash depends on this exact construction,
     * including PMap hint insertion order (custom_model removed, custom_model_files
     * added, then setCustomModel). The custom_model_files value [motorcycle.json] is
     * part of that hash (current graph: motorcycle|198752012) and must not be renamed.
     */
    static Profile motorcycleProfile() {
        CustomModel customModel = new CustomModel();
        customModel.setDistanceInfluence(90.0);
        customModel.addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"));
        customModel.addToPriority(Statement.If("track_type.ordinal() > 1", Statement.Op.MULTIPLY, "0"));
        customModel.addToPriority(Statement.If("road_access == PRIVATE", Statement.Op.MULTIPLY, "0"));
        customModel.addToPriority(Statement.If("road_access == DESTINATION", Statement.Op.MULTIPLY, "0.1"));
        customModel.addToPriority(Statement.If("road_class == MOTORWAY || road_class == TRUNK", Statement.Op.MULTIPLY, "0.1"));
        customModel.addToSpeed(Statement.If("true", Statement.Op.LIMIT, "0.9 * car_average_speed"));
        customModel.addToSpeed(Statement.If("true", Statement.Op.LIMIT, "120"));
        customModel.addToSpeed(Statement.If(
                "surface==COBBLESTONE || surface==GRASS || surface==GRAVEL || surface==SAND || " +
                        "surface==PAVING_STONES || surface==DIRT || surface==GROUND || " +
                        "surface==UNPAVED || surface==COMPACTED",
                Statement.Op.LIMIT, "30"));

        Profile profile = new Profile("motorcycle");
        profile.getHints().remove("custom_model");
        profile.putHint("custom_model_files", List.of("motorcycle.json"));
        profile.setCustomModel(customModel);
        return profile;
    }
}
