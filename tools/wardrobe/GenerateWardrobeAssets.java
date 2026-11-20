import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Genera el catalogo del wardrobe: modelos, texturas y catalog.json.
 *
 * Dos familias:
 *  - Modelos que ya trae el jar: se suben de resolucion (UV de caja -> caras explicitas con la
 *    formula de BedrockMesh.buildBoxQuads) y cada uno recibe tres materiales distintos
 *    (Prism = brillo espectral, Ember = ascuas y grano, Frost = facetas y escarcha).
 *  - Aros y auras generados aqui: geometria procedimental (toros, picos, orbes, runas) y arte
 *    propio por acabado.
 *
 * Las capas son textura pura con disenos distintos entre si; cuatro van animadas
 * (PNG del primer frame + `.100ms.sheet` con la pila vertical de frames).
 */
public final class GenerateWardrobeAssets {

    enum Source { MODEL, CAPE, GENERATED, SCULPTED }

    private record Base(String slug, String name, String type, String slot, Source source,
                        String path, String art, String[] designs) {}

    private static final String[] MATERIALS = {"prism", "ember", "frost"};
    private static final String[] MATERIAL_LABELS = {"Prism", "Ember", "Frost"};

    private static final Base[] BASES = {
        model("halo_angel", "Angel Halo", "hat", "hat", "textures/cosmetics/halo_angel.png"),
        model("halo_cyber", "Cyber Halo", "hat", "hat", "textures/cosmetics/halo_cyber.png"),
        model("halo_demon", "Demon Halo", "hat", "hat", "textures/cosmetics/halo_demon.png"),
        model("halo_sakura", "Sakura Halo", "hat", "hat", "textures/cosmetics/halo_sakura.png"),
        model("halo_void", "Void Crown", "hat", "hat", "textures/cosmetics/halo_void.png"),
        model("nebula_halo", "Nebula Halo", "hat", "hat", "textures/cosmetics/nebula_halo.png"),
        model("santa_hat", "Santa Hat", "hat", "hat", "textures/cosmetics/santa_hat.png"),
        model("reindeer_antlers", "Reindeer Antlers", "hat", "hat", "textures/cosmetics/reindeer_antlers.png"),
        model("wings_valkyrie", "Valkyrie Wings", "wings", "wings", "textures/cosmetics/wings_valkyrie.png"),
        model("wings_dragon", "Dragon Wings", "wings", "wings", "textures/cosmetics/wings_dragon.png"),
        model("wings_cyber", "Cyber Wings", "wings", "wings", "textures/cosmetics/wings_cyber.png"),
        model("wings_snowflake", "Snowflake Wings", "wings", "wings", "textures/cosmetics/wings_snowflake.png"),
        model("nebula_wings", "Nebula Wings", "wings", "wings", "textures/cosmetics/nebula_wings.png"),
        sculpted("shoulder_elf", "Elf Companion", "shoulder", "shoulder", "elf"),
        // Animales modelados aqui: volumen redondeado con muchos cubos pequenos, no cajas.
        sculpted("shoulder_reindeer", "Reindeer Companion", "shoulder", "shoulder", "reindeer"),
        sculpted("shoulder_snowman", "Snowman Companion", "shoulder", "shoulder", "snowman"),
        model("holiday_scarf", "Holiday Scarf", "shoulder", "shoulder", "textures/cosmetics/holiday_scarf.png"),
        sculpted("pet_giftbox", "Giftbox Pet", "pet", "pet", "giftbox"),
        sculpted("pet_reindeer", "Baby Reindeer", "pet", "pet", "reindeer"),

        // Aros nuevos: geometria propia y dos acabados por modelo.
        generated("ring_prism", "Prism Ring", "ring_thin", "rainbow", "prismglass"),
        generated("ring_twin", "Twin Ring", "ring_double", "amethyst", "deepsea"),
        generated("crown_spikes", "Spike Crown", "crown_spikes", "gold", "obsidian"),
        generated("orbit_halo", "Orbit Halo", "orbit_orbs", "planets", "radiance"),

        // Auras: el wardrobe no tenia ninguna.
        generated("aura_ring", "Aura Ring", "aura_ring", "huewheel", "emerald"),
        generated("aura_flames", "Flame Aura", "aura_flames", "lava", "toxic"),
        generated("aura_runes", "Rune Circle", "aura_runes", "arcane", "monochrome"),
        generated("aura_shards", "Crystal Aura", "aura_shards", "sakura", "petalstorm"),

        // Capas: un diseno distinto por capa.
        // Cada capa espacial es una escena distinta, no estrellas con otro color.
        cape("cape_galaxy", "Ringed Planet Cape", "ringed", false),
        cape("cape_aurora", "Aurora Cape", "aurora", false),
        cape("cape_ember", "Ember Cape", "ember", false),
        cape("cape_frost", "Frost Cape", "frost", false),
        cape("cape_cyber", "Cyber Grid Cape", "cyber", false),
        cape("cape_voidrune", "Black Hole Cape", "blackhole", false),
        cape("cape_starfall", "Comet Trail Cape", "comet", false),
        cape("cape_solar", "Solar Flare Cape", "solar", false),
        cape("cape_hex", "Hex Mesh Cape", "hex", false),
        cape("cape_cloud", "Nebula Pillar Cape", "pillars", false),
        cape("cape_crest", "Crimson Crest Cape", "crest", false),
        cape("cape_circuit", "Circuit Cape", "circuit", false),
        cape("cape_aurora_drift", "Aurora Drift Cape", "aurora_drift", true),
        cape("cape_ember_flow", "Ember Flow Cape", "ember_flow", true),
        cape("cape_star_twinkle", "Constellation Cape", "constellation", true),
        cape("cape_holo_shift", "Holo Shift Cape", "holo_shift", true),
    };

    private static final int HD_MIN_SIDE = 256;
    private static final int CAPE_WIDTH = 512;
    private static final int CAPE_HEIGHT = 256;
    private static final int ANIMATED_FRAMES = 8;

    private static Base model(String slug, String name, String type, String slot, String path) {
        return new Base(slug, name, type, slot, Source.MODEL, path, null, MATERIALS);
    }

    private static Base cape(String slug, String name, String art, boolean animated) {
        return new Base(slug, name, "cape", "cape", Source.CAPE, null, art,
            new String[] {animated ? art + "#anim" : art});
    }

    private static Base sculpted(String slug, String name, String type, String slot, String shape) {
        return new Base(slug, name, type, slot, Source.SCULPTED, shape, null, MATERIALS);
    }

    private static Base generated(String slug, String name, String geometry, String... designs) {
        String type = geometry.startsWith("aura") ? "aura" : "hat";
        String slot = geometry.startsWith("aura") ? "aura" : "hat";
        return new Base(slug, name, type, slot, Source.GENERATED, geometry, null, designs);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: GenerateWardrobeAssets <assets/polyplus> <salida>");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);
        Path models = target.resolve("models");
        Path textures = target.resolve("textures");
        Files.createDirectories(models);
        Files.createDirectories(textures);

        // Borra la generacion anterior para no dejar texturas huerfanas.
        try (var walk = Files.walk(target)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (!p.getFileName().toString().equals("catalog.json")) p.toFile().delete();
            }
        }

        List<String> entries = new ArrayList<>();
        int converted = 0;

        for (Base base : BASES) {
            switch (base.source()) {
                case MODEL -> {
                    Path geo = source.resolve("cosmetics/" + base.slug() + ".geo.json");
                    if (!Files.isRegularFile(geo)) {
                        System.err.println("falta el modelo " + geo);
                        continue;
                    }
                    BufferedImage art = ImageIO.read(source.resolve(base.path()).toFile());
                    int factor = Math.max(1, Math.round((float) HD_MIN_SIDE / Math.min(art.getWidth(), art.getHeight())));
                    BufferedImage hd = upscale(art, factor);

                    boolean companion = COMPANIONS.contains(base.slug());
                    JsonObject geometry = JsonParser.parseString(Files.readString(geo, StandardCharsets.UTF_8)).getAsJsonObject();
                    converted += scaleGeometryUv(geometry, factor);
                    if (companion) {
                        // Mascotas y companeros: mas grandes que en el jar y con piezas propias
                        // (ojos, botones, cascabel, borla) para que no sean cuatro cubos planos.
                        scaleCompanion(geometry, COMPANION_SCALE);
                        decorateCompanion(geometry, base.slug(), paintDetailStrip(hd), hd.getWidth(), hd.getHeight());
                    }
                    Files.writeString(models.resolve(base.slug() + ".geo.json"),
                        factor <= 1 && !companion ? Files.readString(geo, StandardCharsets.UTF_8)
                                     : new GsonBuilder().create().toJson(geometry) + "\n",
                        StandardCharsets.UTF_8);
                    Path animation = source.resolve("cosmetics/" + base.slug() + ".animation.json");
                    String animationPath = null;
                    if (Files.isRegularFile(animation)) {
                        // El jar trae animaciones con comas finales; se normalizan antes de servirlas
                        // porque el parser estricto del cliente rechaza el JSON tal cual.
                        String raw = Files.readString(animation, StandardCharsets.UTF_8)
                            .replaceAll(",\\s*([}\\]])", "$1");
                        JsonElement parsed = JsonParser.parseString(raw);
                        Files.writeString(models.resolve(base.slug() + ".animation.json"),
                            new GsonBuilder().create().toJson(parsed) + "\n", StandardCharsets.UTF_8);
                        animationPath = "models/" + base.slug() + ".animation.json";
                    }

                    for (int i = 0; i < MATERIALS.length; i++) {
                        String slug = base.slug() + "-" + MATERIALS[i];
                        String png = "textures/" + slug + ".png";
                        BufferedImage out = applyMaterial(hd, MATERIALS[i], i, base.slug());
                        ImageIO.write(out, "png", textures.resolve(slug + ".png").toFile());
                        entries.add(entryJson(slug, base, base.name() + " (" + MATERIAL_LABELS[i] + ")",
                            png, "models/" + base.slug() + ".geo.json", animationPath, null));
                    }
                }
                case CAPE -> {
                    String art = base.designs()[0];
                    boolean animated = art.endsWith("#anim");
                    String theme = animated ? art.substring(0, art.length() - 5) : art;
                    int frames = animated ? ANIMATED_FRAMES : 1;

                    BufferedImage[] images = new BufferedImage[frames];
                    for (int f = 0; f < frames; f++) {
                        images[f] = render(theme, CAPE_WIDTH, CAPE_HEIGHT, f / (double) frames);
                    }

                    String png = "textures/" + base.slug() + ".png";
                    ImageIO.write(images[0], "png", textures.resolve(base.slug() + ".png").toFile());

                    String sheet = null;
                    if (animated) {
                        BufferedImage stacked = new BufferedImage(CAPE_WIDTH, CAPE_HEIGHT * frames, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = stacked.createGraphics();
                        for (int f = 0; f < frames; f++) g.drawImage(images[f], 0, f * CAPE_HEIGHT, null);
                        g.dispose();
                        sheet = "textures/" + base.slug() + ".100ms.sheet";
                        ImageIO.write(stacked, "png", textures.resolve(base.slug() + ".100ms.sheet").toFile());
                    }
                    entries.add(entryJson(base.slug(), base, base.name(), png, null, null, sheet));
                }
                case SCULPTED -> {
                    Path origin = source.resolve("cosmetics/" + base.slug() + ".geo.json");
                    if (!Files.isRegularFile(origin)) {
                        System.err.println("falta el modelo " + origin);
                        continue;
                    }
                    JsonObject sourceGeometry = JsonParser.parseString(Files.readString(origin, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject built = buildCompanion(base.path(), base.slug(), geometryBody(sourceGeometry));
                    Files.writeString(models.resolve(base.slug() + ".geo.json"),
                        new GsonBuilder().create().toJson(built) + "\n", StandardCharsets.UTF_8);

                    String animationPath = null;
                    Path animation = source.resolve("cosmetics/" + base.slug() + ".animation.json");
                    if (Files.isRegularFile(animation)) {
                        String raw = Files.readString(animation, StandardCharsets.UTF_8)
                            .replaceAll(",\\s*([}\\]])", "$1");
                        JsonElement parsed = JsonParser.parseString(raw);
                        Files.writeString(models.resolve(base.slug() + ".animation.json"),
                            new GsonBuilder().create().toJson(parsed) + "\n", StandardCharsets.UTF_8);
                        animationPath = "models/" + base.slug() + ".animation.json";
                    }

                    for (int i = 0; i < MATERIALS.length; i++) {
                        String slug = base.slug() + "-" + MATERIALS[i];
                        String png = "textures/" + slug + ".png";
                        BufferedImage painted = applyMaterial(paletteAtlas(), MATERIALS[i], i, base.slug());
                        // Fuera de la franja de paleta el atlas va limpio: los brillos del material
                        // solo dejarian niebla en zonas que ninguna cara usa.
                        for (int y = 0; y < PAL_ROW; y++) {
                            for (int x = 0; x < painted.getWidth(); x++) painted.setRGB(x, y, 0);
                        }
                        ImageIO.write(painted, "png", textures.resolve(slug + ".png").toFile());
                        entries.add(entryJson(slug, base, base.name() + " (" + MATERIAL_LABELS[i] + ")",
                            png, "models/" + base.slug() + ".geo.json", animationPath, null));
                    }
                }
                case GENERATED -> {
                    Files.writeString(models.resolve(base.slug() + ".geo.json"),
                        new GsonBuilder().create().toJson(buildGeometry(base.path(), base.slug())) + "\n",
                        StandardCharsets.UTF_8);
                    for (String design : base.designs()) {
                        String slug = base.slug() + "-" + design;
                        String png = "textures/" + slug + ".png";
                        ImageIO.write(render(design, 256, 256, 0), "png", textures.resolve(slug + ".png").toFile());
                        entries.add(entryJson(slug, base, base.name() + " (" + capitalize(design) + ")",
                            png, "models/" + base.slug() + ".geo.json", null, null));
                    }
                }
            }
        }

        StringBuilder json = new StringBuilder("{\n  \"items\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            json.append("    ").append(entries.get(i)).append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(target.resolve("catalog.json"), json.toString(), StandardCharsets.UTF_8);

        System.out.println("cosmeticos: " + entries.size());
        System.out.println("cubos reescalados (UV caja -> caras): " + converted);
        System.out.println("modelos: " + Files.list(models).count() + " | texturas: " + Files.list(textures).count());
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String entryJson(String slug, Base base, String label, String texture, String model,
                                    String animation, String sheet) {
        StringBuilder out = new StringBuilder("{");
        out.append("\"slug\":\"").append(slug).append("\",");
        out.append("\"base\":\"").append(base.slug()).append("\",");
        out.append("\"name\":\"").append(escape(label)).append("\",");
        out.append("\"type\":\"").append(base.type()).append("\",");
        out.append("\"slots\":[\"").append(base.slot()).append("\"],");
        out.append("\"model\":").append(model == null ? "null" : "\"" + model + "\"").append(',');
        out.append("\"animation\":").append(animation == null ? "null" : "\"" + animation + "\"").append(',');
        out.append("\"texture\":\"").append(texture).append("\",");
        out.append("\"sheet\":").append(sheet == null ? "null" : "\"" + sheet + "\"");
        out.append('}');
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // =====================================================================================
    // Geometria: reescalado del atlas de los modelos del jar
    // =====================================================================================

    private static int scaleGeometryUv(JsonObject root, int factor) {
        JsonArray geometries = root.has("minecraft:geometry")
            ? root.getAsJsonArray("minecraft:geometry")
            : root.has("geometry") && root.get("geometry").isJsonArray() ? root.getAsJsonArray("geometry") : null;
        if (geometries == null || geometries.size() == 0) return 0;

        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.has("description") ? geometry.getAsJsonObject("description") : null;
        if (description != null) {
            int width = description.has("texture_width") ? description.get("texture_width").getAsInt() : 64;
            int height = description.has("texture_height") ? description.get("texture_height").getAsInt() : 64;
            description.addProperty("texture_width", width * factor);
            description.addProperty("texture_height", height * factor);
        }

        int converted = 0;
        JsonArray bones = geometry.has("bones") ? geometry.getAsJsonArray("bones") : new JsonArray();
        for (JsonElement boneElement : bones) {
            JsonObject bone = boneElement.getAsJsonObject();
            if (!bone.has("cubes")) continue;
            for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                JsonObject cube = cubeElement.getAsJsonObject();
                if (!cube.has("uv")) continue;
                JsonElement uv = cube.get("uv");
                if (uv.isJsonArray() && uv.getAsJsonArray().size() >= 2) {
                    expandBoxUv(cube, uv.getAsJsonArray(), factor);
                    converted++;
                } else if (uv.isJsonObject()) {
                    scaleFaceUv(uv.getAsJsonObject(), factor);
                }
            }
        }
        return converted;
    }

    /** Misma particion que `BedrockMesh.buildBoxQuads`; la cara de abajo va con altura negativa. */
    private static void expandBoxUv(JsonObject cube, JsonArray boxUv, int factor) {
        double[] size = vec3(cube, "size", new double[] {1, 1, 1});
        double uvWidth = Math.floor(Math.abs(size[0]));
        double uvHeight = Math.floor(Math.abs(size[1]));
        double uvDepth = Math.floor(Math.abs(size[2]));

        double u0 = boxUv.get(0).getAsDouble();
        double v0 = boxUv.get(1).getAsDouble();
        double u1 = u0 + uvDepth;
        double u2 = u0 + uvDepth + uvWidth;
        double u22 = u2 + uvWidth;
        double u3 = u0 + uvDepth + uvWidth + uvDepth;
        double u4 = u3 + uvWidth;
        double v1 = v0 + uvDepth;
        double v2 = v0 + uvDepth + uvHeight;

        JsonObject faces = new JsonObject();
        faces.add("down", face(u1, v1, uvWidth, v0 - v1, factor));
        faces.add("up", face(u2, v0, uvWidth, v1 - v0, factor));
        faces.add("west", face(u0, v1, uvDepth, v2 - v1, factor));
        faces.add("north", face(u1, v1, uvWidth, v2 - v1, factor));
        faces.add("east", face(u2, v1, uvDepth, v2 - v1, factor));
        faces.add("south", face(u3, v1, uvWidth, v2 - v1, factor));
        cube.add("uv", faces);
    }

    private static JsonObject face(double u, double v, double w, double h, int factor) {
        JsonObject face = new JsonObject();
        JsonArray uv = new JsonArray();
        uv.add(u * factor);
        uv.add(v * factor);
        JsonArray uvSize = new JsonArray();
        uvSize.add(w * factor);
        uvSize.add(h * factor);
        face.add("uv", uv);
        face.add("uv_size", uvSize);
        return face;
    }

    private static void scaleFaceUv(JsonObject faces, int factor) {
        for (String name : new String[] {"down", "up", "west", "north", "east", "south"}) {
            if (!faces.has(name)) continue;
            JsonObject face = faces.getAsJsonObject(name);
            scaleArray(face, "uv", factor);
            scaleArray(face, "uv_size", factor);
        }
    }

    private static void scaleArray(JsonObject owner, String key, int factor) {
        if (!owner.has(key) || !owner.get(key).isJsonArray()) return;
        JsonArray array = owner.getAsJsonArray(key);
        for (int i = 0; i < array.size(); i++) {
            array.set(i, new com.google.gson.JsonPrimitive(array.get(i).getAsDouble() * factor));
        }
    }

    private static double[] vec3(JsonObject object, String key, double[] fallback) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return fallback;
        JsonArray array = object.getAsJsonArray(key);
        double[] out = fallback.clone();
        for (int i = 0; i < Math.min(array.size(), 3); i++) out[i] = array.get(i).getAsDouble();
        return out;
    }

    // =====================================================================================
    // Companeros modelados: cuerpos redondeados con cubos pequenos y sombreado por cara
    // =====================================================================================

    private static final int PAL_BLOCK = 8;
    private static final int PAL_ROW = 248;

    /**
     * Luminancias de la paleta; el material las retinta con su rampa.
     * Cuerpo, panza, nieve, pezuna y asta van en ternas (oscuro / medio / claro) para que
     * `shadedCube` pueda iluminar la cara de arriba y oscurecer la de abajo.
     */
    private static final double[] PALETTE = {
        0.30, 0.46, 0.62,
        0.70, 0.82, 0.94,
        0.86, 0.93, 1.00,
        0.08, 0.15, 0.22,
        0.52, 0.64, 0.76,
        0.03,
        1.00,
        0.72,
        0.42,
        0.34,
        0.26, 0.38, 0.50,
    };
    private static final int PAL_BODY = 1;
    private static final int PAL_BODY_LIGHT = 4;
    private static final int PAL_SNOW = 7;
    private static final int PAL_HOOF = 10;
    private static final int PAL_ANTLER = 13;
    private static final int PAL_EYE = 15;
    private static final int PAL_NOSE = 16;
    private static final int PAL_GOLD = 17;
    private static final int PAL_RED = 18;
    private static final int PAL_WOOD = 19;
    private static final int PAL_SUIT = 21;
    private static final int PAL_BELLY = PAL_BODY_LIGHT;

    /** Solo las ternas se aclaran/oscurcen por cara; un color suelto se queda tal cual. */
    private static int faceShade(int shade, int offset) {
        return switch (shade) {
            case PAL_BODY, PAL_BODY_LIGHT, PAL_SNOW, PAL_HOOF, PAL_ANTLER, PAL_SUIT -> shade + offset;
            default -> shade;
        };
    }

    private static BufferedImage paletteAtlas() {
        BufferedImage atlas = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < PALETTE.length; i++) {
            int level = (int) Math.round(PALETTE[i] * 255);
            int argb = 0xFF000000 | (level << 16) | (level << 8) | level;
            for (int y = 0; y < PAL_BLOCK; y++) {
                for (int x = 0; x < PAL_BLOCK; x++) {
                    atlas.setRGB(i * PAL_BLOCK + x, PAL_ROW + y, argb);
                }
            }
        }
        return atlas;
    }

    private static void faceBlock(JsonObject faces, String name, int block) {
        int index = Math.max(0, Math.min(PALETTE.length - 1, block));
        JsonObject face = new JsonObject();
        face.add("uv", vec(index * PAL_BLOCK, PAL_ROW));
        face.add("uv_size", vec(PAL_BLOCK, PAL_BLOCK));
        faces.add(name, face);
    }

    /** Cubo con la cara de arriba mas clara y la de abajo mas oscura que los lados. */
    private static void shadedCube(JsonArray cubes, double x, double y, double z,
                                   double sx, double sy, double sz, int shade) {
        JsonObject cube = new JsonObject();
        cube.add("origin", vec(x, y, z));
        cube.add("size", vec(sx, sy, sz));
        JsonObject faces = new JsonObject();
        faceBlock(faces, "up", faceShade(shade, 1));
        faceBlock(faces, "down", faceShade(shade, -1));
        for (String name : new String[] {"west", "north", "east", "south"}) {
            faceBlock(faces, name, shade);
        }
        cube.add("uv", faces);
        cubes.add(cube);
    }

    /** Volumen tipo esfera hecho de cubos pequenos: la clave para que no sea una caja. */
    private static void blob(JsonArray cubes, double cx, double cy, double cz,
                             double rx, double ry, double rz, double step, int shade) {
        for (double x = cx - rx + step / 2; x <= cx + rx; x += step) {
            for (double y = cy - ry + step / 2; y <= cy + ry; y += step) {
                for (double z = cz - rz + step / 2; z <= cz + rz; z += step) {
                    double dx = (x - cx) / rx;
                    double dy = (y - cy) / ry;
                    double dz = (z - cz) / rz;
                    if (dx * dx + dy * dy + dz * dz > 1.0) continue;
                    // Solo la cascara: los cubos de dentro no se ven y engordan el modelo.
                    if (Math.abs(dx) < 1 - Math.min(1, step * 1.6 / rx)
                        && Math.abs(dy) < 1 - Math.min(1, step * 1.6 / ry)
                        && Math.abs(dz) < 1 - Math.min(1, step * 1.6 / rz)) continue;
                    double size = step * 1.18;
                    shadedCube(cubes, x - size / 2, y - size / 2, z - size / 2, size, size, size, shade);
                }
            }
        }
    }

    /**
     * Cadena continua de cubos: cuernos, ramas y zanahorias con punta fina.
     * (x,y,z) es el arranque y (dx,dy,dz) el vector total hasta la punta; el tamano nunca baja
     * del paso para que la pieza quede solida y no salga como cuentas sueltas.
     */
    private static void chain(JsonArray cubes, double x, double y, double z, double dx, double dy, double dz,
                              int steps, double start, double end, int shade) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0) return;
        double ux = dx / length;
        double uy = dy / length;
        double uz = dz / length;
        double spacing = length / steps;
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            double size = Math.max(spacing * 1.5, start + (end - start) * f);
            double cx = x + ux * spacing * i;
            double cy = y + uy * spacing * i;
            double cz = z + uz * spacing * i;
            shadedCube(cubes, cx - size / 2, cy - size / 2, cz - size / 2, size, size, size, shade);
        }
    }

    /** Pata que se afina hacia abajo, con pezuna oscura. */
    private static void limb(JsonArray cubes, double x, double z, double top, double bottom,
                             double radiusTop, double radiusBottom, int shade, int hoof) {
        double step = 0.55;
        int count = (int) Math.max(1, Math.round((top - bottom) / step));
        for (int i = 0; i < count; i++) {
            double f = i / (double) count;
            double y = top - (top - bottom) * f;
            double radius = radiusTop + (radiusBottom - radiusTop) * f;
            double size = radius * 2;
            shadedCube(cubes, x - size / 2, y - step * 0.65, z - size / 2, size, step * 1.2, size, shade);
        }
        shadedCube(cubes, x - 0.52, bottom, z - 0.58, 1.04, 0.45, 1.16, hoof);
    }

    private static JsonObject bone(String name, String parent, double[] pivot) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        if (parent != null) bone.addProperty("parent", parent);
        bone.add("pivot", vec(pivot[0], pivot[1], pivot[2]));
        return bone;
    }

    private static JsonObject buildCompanion(String shape, String slug, JsonObject reference) {
        double[] anchor = anchorOf(reference.getAsJsonArray("bones"));

        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        JsonArray geometries = new JsonArray();
        JsonObject geometry = new JsonObject();
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry." + slug);
        description.addProperty("texture_width", 256);
        description.addProperty("texture_height", 256);
        description.addProperty("visible_bounds_width", 3);
        description.addProperty("visible_bounds_height", 3);
        description.add("visible_bounds_offset", vec(0, 1.5, 0));
        geometry.add("description", description);

        JsonArray bones = new JsonArray();
        bones.add(bone("body", null, new double[] {0, 24, 0}));
        switch (shape) {
            case "reindeer" -> reindeer(bones, slug, anchor);
            case "snowman" -> snowman(bones, anchor);
            case "elf" -> elf(bones, anchor);
            case "giftbox" -> giftbox(bones, anchor);
            default -> throw new IllegalArgumentException("forma desconocida: " + shape);
        }
        geometry.add("bones", bones);
        geometries.add(geometry);
        root.add("minecraft:geometry", geometries);
        return root;
    }

    private static void addBone(JsonArray bones, String name, String parent, double[] pivot, JsonArray cubes) {
        JsonObject node = bone(name, parent, pivot);
        if (cubes.size() > 0) node.add("cubes", cubes);
        bones.add(node);
    }

    /**
     * Reno. El de mascota es un cervatillo: patas cortas, cabeza proporcionada y cuernos de
     * boton; el de hombro es el adulto, con la cornamenta completa y tres puntas por lado.
     */
    private static void reindeer(JsonArray bones, String slug, double[] a) {
        String prefix = slug.startsWith("pet_") ? "pet" : "reindeer";
        boolean fawn = slug.startsWith("pet_");
        double ax = a[0];
        double ay = a[1];
        double az = a[2];

        double bodyY = ay + (fawn ? 4.3 : 5.4);
        double bodyZ = az + 0.3;
        double bodyRX = fawn ? 1.95 : 2.15;
        double bodyRY = fawn ? 1.7 : 1.95;
        double bodyRZ = fawn ? 2.55 : 3.25;

        bones.add(bone(prefix + "_root", "body", a));

        JsonArray torso = new JsonArray();
        blob(torso, ax, bodyY, bodyZ, bodyRX, bodyRY, bodyRZ, fawn ? 0.78 : 0.88, PAL_BODY);
        // Pecho claro pegado a la cara de delante: si va dentro del torso solo deja hilachas.
        shadedCube(torso, ax - bodyRX * 0.55, bodyY - bodyRY * 0.55, bodyZ - bodyRZ * 0.94,
            bodyRX * 1.1, bodyRY * 0.75, 0.34, PAL_BELLY);
        addBone(bones, prefix + "_torso", prefix + "_root", new double[] {ax, bodyY, bodyZ}, torso);

        double tailY = bodyY + bodyRY * 0.25;
        double tailZ = bodyZ + bodyRZ * 0.92;
        JsonArray tail = new JsonArray();
        blob(tail, ax, tailY, tailZ, 0.66, 0.8, 0.55, 0.5, PAL_BODY);
        addBone(bones, prefix + "_tail", prefix + "_torso", new double[] {ax, tailY, tailZ}, tail);

        double neckY = bodyY + (fawn ? 1.9 : 2.5);
        double neckZ = bodyZ - (fawn ? 1.25 : 1.8);
        JsonArray neck = new JsonArray();
        blob(neck, ax, neckY, neckZ, 1.15, 1.45, 1.15, 0.72, PAL_BODY);
        addBone(bones, prefix + "_neck", prefix + "_torso", new double[] {ax, neckY, neckZ}, neck);

        double headY = neckY + (fawn ? 1.75 : 2.15);
        double headZ = neckZ - (fawn ? 0.95 : 1.2);
        double headRX = fawn ? 1.5 : 1.45;
        double headRY = fawn ? 1.4 : 1.35;
        double headRZ = fawn ? 1.6 : 1.8;

        JsonArray head = new JsonArray();
        blob(head, ax, headY, headZ, headRX, headRY, headRZ, 0.66, PAL_BODY);
        blob(head, ax, headY - headRY * 0.35, headZ - headRZ * 0.78,
            0.78, 0.62, 0.9, 0.58, PAL_BELLY);
        double faceZ = headZ - headRZ;
        double eyeSize = fawn ? 0.62 : 0.54;
        for (double side : new double[] {-1, 1}) {
            shadedCube(head, ax + side * (fawn ? 0.6 : 0.56) - eyeSize / 2,
                headY + headRY * 0.2, faceZ - 0.16, eyeSize, eyeSize * 0.94, 0.3, PAL_EYE);
        }
        for (double side : new double[] {-1, 1}) {
            blob(head, ax + side * (fawn ? 1.28 : 1.34), headY + headRY * (fawn ? 0.5 : 0.68), headZ + 0.12,
                fawn ? 0.34 : 0.42, fawn ? 0.5 : 0.72, fawn ? 0.4 : 0.46, fawn ? 0.4 : 0.46, PAL_BODY);
        }
        shadedCube(head, ax - 0.32, headY - headRY - 0.6, headZ - headRZ * 0.45,
            0.64, 0.58, 0.52, PAL_GOLD);
        addBone(bones, prefix + "_head", prefix + "_neck", new double[] {ax, headY, headZ}, head);

        double noseY = headY - headRY * 0.35;
        double noseZ = headZ - headRZ;
        JsonArray nose = new JsonArray();
        shadedCube(nose, ax - 0.36, noseY, noseZ - 0.32, 0.72, 0.66, 0.62, PAL_NOSE);
        addBone(bones, "nose_glow15", prefix + "_head", new double[] {ax, noseY, noseZ}, nose);

        double antlerX = fawn ? 0.95 : 1.05;
        double antlerY = headY + headRY * 0.85;
        double antlerZ = headZ + 0.15;
        for (double side : new double[] {-1, 1}) {
            JsonArray antler = new JsonArray();
            if (fawn) {
                chain(antler, ax + side * antlerX, antlerY, antlerZ,
                    side * 0.4, 1.1, 0.25, 3, 0.46, 0.32, PAL_ANTLER);
                chain(antler, ax + side * (antlerX + 0.15), antlerY + 0.7, antlerZ + 0.15,
                    side * 0.5, 0.3, -0.3, 2, 0.32, 0.24, PAL_ANTLER);
            } else {
                chain(antler, ax + side * antlerX, antlerY, antlerZ,
                    side * 1.5, 3.0, 0.5, 7, 0.52, 0.28, PAL_ANTLER);
                chain(antler, ax + side * (antlerX + 0.8), antlerY + 1.4, antlerZ + 0.25,
                    side * 1.35, 0.35, -0.45, 4, 0.38, 0.24, PAL_ANTLER);
                chain(antler, ax + side * (antlerX + 0.6), antlerY + 2.45, antlerZ + 0.4,
                    side * 1.25, 0.3, -0.55, 3, 0.34, 0.22, PAL_ANTLER);
            }
            addBone(bones, side > 0 ? "antler_left" : "antler_right", prefix + "_head",
                new double[] {ax + side * antlerX, antlerY, antlerZ}, antler);
        }

        double legTop = ay + (fawn ? 2.9 : 3.5);
        double legRadius = fawn ? 0.5 : 0.6;
        double legX = fawn ? 1.0 : 1.15;
        double legZ = fawn ? 1.5 : 1.95;
        String[][] legs = {
            {"leg_front_left", "1", "-"}, {"leg_front_right", "-1", "-"},
            {"leg_back_left", "1", "+"}, {"leg_back_right", "-1", "+"},
        };
        for (String[] leg : legs) {
            double lx = ax + legX * Double.parseDouble(leg[1]);
            double lz = bodyZ + legZ * (leg[2].equals("+") ? 1 : -1);
            JsonArray cubes = new JsonArray();
            limb(cubes, lx, lz, legTop, ay + 0.4, legRadius, legRadius * 0.72, PAL_BODY, PAL_HOOF);
            addBone(bones, leg[0], prefix + "_torso", new double[] {lx, legTop - 0.5, lz}, cubes);
        }
    }

    /** Muneco de nieve: tres bolas, cara de carbon, bufanda, brazos de rama y sombrero de copa. */
    private static void snowman(JsonArray bones, double[] a) {
        double ax = a[0];
        double ay = a[1];
        double az = a[2];
        bones.add(bone("snowman_root", "body", a));

        double baseR = 2.2;
        double torsoR = 1.7;
        double headR = 1.35;
        double baseY = ay + baseR * 0.92;
        double torsoY = baseY + baseR * 0.7 + torsoR * 0.5;
        double headY = torsoY + torsoR * 0.75 + headR * 0.5;

        JsonArray base = new JsonArray();
        blob(base, ax, baseY, az, baseR, baseR * 0.95, baseR, 0.9, PAL_SNOW);
        addBone(bones, "snow_base", "snowman_root", new double[] {ax, baseY, az}, base);

        JsonArray torso = new JsonArray();
        blob(torso, ax, torsoY, az, torsoR, torsoR * 0.98, torsoR, 0.78, PAL_SNOW);
        for (int i = 0; i < 3; i++) {
            shadedCube(torso, ax - 0.24, torsoY - 0.55 + i * 0.85, az - torsoR * 1.0,
                0.48, 0.48, 0.34, PAL_EYE);
        }
        // Bufanda: banda frontal y cola colgando, no una losa que tapa el torso.
        shadedCube(torso, ax - torsoR * 0.98, torsoY + torsoR * 0.72, az - torsoR * 1.02,
            torsoR * 1.96, 0.44, 0.4, PAL_RED);
        shadedCube(torso, ax - torsoR * 1.04, torsoY + torsoR * 0.72, az - torsoR * 0.4,
            0.42, 0.44, torsoR * 1.3, PAL_RED);
        shadedCube(torso, ax - 0.36, torsoY + 0.2, az - torsoR * 1.02, 0.72, 1.45, 0.36, PAL_RED);
        addBone(bones, "snow_torso", "snow_base", new double[] {ax, torsoY, az}, torso);

        JsonArray head = new JsonArray();
        blob(head, ax, headY, az, headR, headR, headR, 0.66, PAL_SNOW);
        double faceZ = az - headR;
        for (double side : new double[] {-0.55, 0.55}) {
            shadedCube(head, ax + side - 0.19, headY + 0.32, faceZ - 0.2, 0.38, 0.38, 0.34, PAL_EYE);
        }
        for (int i = 0; i < 4; i++) {
            double t = (i - 1.5) / 1.5;
            shadedCube(head, ax + t * 0.72 - 0.13,
                headY - 0.42 - (1 - t * t) * 0.26, faceZ - 0.16, 0.26, 0.26, 0.3, PAL_EYE);
        }
        chain(head, ax, headY, az - headR * 0.9, 0, -0.2, -1.4, 4, 0.46, 0.2, PAL_GOLD);
        addBone(bones, "snow_head", "snow_torso", new double[] {ax, headY, az}, head);

        JsonArray hat = new JsonArray();
        double hatY = headY + headR * 0.85;
        shadedCube(hat, ax - 1.45, hatY, az - 1.45, 2.9, 0.42, 2.9, PAL_HOOF + 1);
        shadedCube(hat, ax - 1.1, hatY + 0.42, az - 1.1, 2.2, 2.0, 2.2, PAL_HOOF);
        shadedCube(hat, ax - 1.14, hatY + 0.58, az - 1.14, 2.28, 0.5, 2.28, PAL_RED);
        addBone(bones, "top_hat", "snow_head", new double[] {ax, hatY, az}, hat);

        for (double side : new double[] {-1, 1}) {
            double sx = ax + side * torsoR * 0.95;
            JsonArray arm = new JsonArray();
            chain(arm, sx, torsoY + 0.6, az, side * 1.6, 0.45, 0, 4, 0.36, 0.24, PAL_WOOD);
            chain(arm, ax + side * (torsoR * 0.95 + 0.95), torsoY + 0.95, az,
                side * 0.7, 0.75, 0.2, 3, 0.26, 0.18, PAL_WOOD);
            addBone(bones, side > 0 ? "arm_left" : "arm_right", "snow_torso",
                new double[] {sx, torsoY + 0.6, az}, arm);
        }
    }

    /** Elfo: botas, piernas, tunica con cinturon, brazos con manos, cabeza, orejas y gorro. */
    private static void elf(JsonArray bones, double[] a) {
        double ax = a[0];
        double ay = a[1];
        double az = a[2];
        bones.add(bone("elf_root", "body", a));

        double legTop = ay + 3.4;
        for (double side : new double[] {-1, 1}) {
            double lx = ax + side * 0.8;
            JsonArray leg = new JsonArray();
            limb(leg, lx, az, legTop + 0.4, ay + 0.6, 0.52, 0.46, PAL_SUIT, PAL_HOOF);
            addBone(bones, side > 0 ? "leg_left" : "leg_right", "elf_root",
                new double[] {lx, legTop + 0.4, az}, leg);
        }

        double torsoY = ay + 5.3;
        JsonArray torso = new JsonArray();
        blob(torso, ax, torsoY, az, 1.6, 1.85, 1.5, 0.78, PAL_SUIT);
        shadedCube(torso, ax - 1.62, torsoY - 0.95, az - 1.52, 3.24, 0.5, 3.04, PAL_HOOF);
        shadedCube(torso, ax - 0.4, torsoY - 0.98, az - 1.62, 0.8, 0.54, 0.22, PAL_GOLD);
        shadedCube(torso, ax - 1.2, torsoY + 1.55, az - 1.2, 2.4, 0.4, 2.4, PAL_SNOW);
        addBone(bones, "elf_torso", "elf_root", new double[] {ax, torsoY, az}, torso);

        for (double side : new double[] {-1, 1}) {
            double sx = ax + side * 1.45;
            JsonArray arm = new JsonArray();
            chain(arm, sx, torsoY + 1.0, az, side * 0.75, -1.7, 0.1, 4, 0.44, 0.34, PAL_SUIT);
            shadedCube(arm, ax + side * 2.16 - 0.28, torsoY - 0.95, az - 0.3,
                0.56, 0.5, 0.56, PAL_BELLY);
            addBone(bones, side > 0 ? "arm_left" : "arm_right", "elf_torso",
                new double[] {sx, torsoY + 1.0, az}, arm);
        }

        double headY = ay + 8.4;
        JsonArray head = new JsonArray();
        blob(head, ax, headY, az, 1.3, 1.3, 1.25, 0.62, PAL_BELLY);
        for (double side : new double[] {-0.5, 0.5}) {
            shadedCube(head, ax + side - 0.17, headY + 0.2, az - 1.4, 0.34, 0.34, 0.34, PAL_EYE);
        }
        shadedCube(head, ax - 0.2, headY - 0.18, az - 1.42, 0.4, 0.36, 0.34, PAL_BELLY);
        for (double side : new double[] {-1, 1}) {
            blob(head, ax + side * 1.22, headY - 0.05, az, 0.3, 0.45, 0.3, 0.4, PAL_BELLY);
        }
        shadedCube(head, ax - 1.2, headY + 1.05, az - 1.2, 2.4, 0.38, 2.4, PAL_SNOW);
        addBone(bones, "elf_head", "elf_torso", new double[] {ax, headY, az}, head);

        JsonArray hat = new JsonArray();
        chain(hat, ax, headY + 1.2, az, 0.55, 2.6, 0.35, 8, 1.45, 0.32, PAL_SUIT);
        shadedCube(hat, ax + 0.55 - 0.36, headY + 3.7, az + 0.35 - 0.36, 0.72, 0.72, 0.72, PAL_SNOW);
        addBone(bones, "elf_hat_base", "elf_head", new double[] {ax, headY + 1.2, az}, new JsonArray());
        addBone(bones, "elf_hat_tip", "elf_hat_base", new double[] {ax + 0.55, headY + 3.7, az + 0.35}, hat);
    }

    /** Regalo: caja redondeada, tapa, cintas cruzadas y lazo de cuatro bucles. */
    private static void giftbox(JsonArray bones, double[] a) {
        double ax = a[0];
        double ay = a[1];
        double az = a[2];
        bones.add(bone("gift_root", "body", a));

        double boxY = ay + 2.3;
        double boxR = 2.0;
        JsonArray box = new JsonArray();
        blob(box, ax, boxY, az, boxR, 1.9, boxR, 0.92, PAL_RED);
        shadedCube(box, ax - boxR - 0.02, ay + 0.35, az - 0.32, boxR * 2.04, 3.9, 0.64, PAL_GOLD);
        shadedCube(box, ax - 0.32, ay + 0.35, az - boxR - 0.02, 0.64, 3.9, boxR * 2.04, PAL_GOLD);
        addBone(bones, "box_base", "gift_root", new double[] {ax, boxY, az}, box);

        double lidY = ay + 4.35;
        double lidR = 2.15;
        JsonArray lid = new JsonArray();
        blob(lid, ax, lidY, az, lidR, 0.6, lidR, 0.78, PAL_RED);
        shadedCube(lid, ax - lidR - 0.02, ay + 4.15, az - 0.3, lidR * 2.04, 0.6, 0.6, PAL_GOLD);
        shadedCube(lid, ax - 0.3, ay + 4.15, az - lidR - 0.02, 0.6, 0.6, lidR * 2.04, PAL_GOLD);
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI / 2 * i + Math.PI / 4;
            blob(lid, ax + Math.cos(angle) * 1.15, lidY + 1.15, az + Math.sin(angle) * 1.15,
                0.92, 0.72, 0.92, 0.5, PAL_GOLD);
        }
        shadedCube(lid, ax - 0.45, lidY + 0.85, az - 0.45, 0.9, 0.9, 0.9, PAL_GOLD);
        addBone(bones, "box_lid", "box_base", new double[] {ax, lidY, az}, lid);
    }

    // =====================================================================================
    // Companeros: mascotas y hombro, mas grandes y con detalle añadido
    // =====================================================================================

    private static final java.util.Set<String> COMPANIONS = java.util.Set.of(
        "pet_reindeer", "shoulder_reindeer", "shoulder_snowman", "shoulder_elf", "pet_giftbox");
    private static final double COMPANION_SCALE = 1.45;
    private static final int DETAIL_BLOCK = 16;

    /** Indices de color del cinturon de detalle que se pinta al pie del atlas. */
    private static final int DETAIL_BLACK = 0;
    private static final int DETAIL_WHITE = 1;
    private static final int DETAIL_GOLD = 2;
    private static final int DETAIL_RED = 3;

    private static final int[][] DETAIL_COLORS = {
        {20, 18, 24}, {246, 248, 255}, {232, 184, 64}, {198, 42, 54},
    };

    /** Cuerpo de la geometria: `minecraft:geometry[0]`. */
    private static JsonObject geometryBody(JsonObject root) {
        if (root.has("minecraft:geometry")) {
            return root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        }
        if (root.has("geometry") && root.get("geometry").isJsonArray()) {
            return root.getAsJsonArray("geometry").get(0).getAsJsonObject();
        }
        return root;
    }

    /** Agranda el modelo alrededor del hueso que cuelga de `body`, asi no se despega del hombro. */
    private static void scaleCompanion(JsonObject root, double factor) {
        JsonObject geometry = geometryBody(root);
        JsonArray bones = geometry.has("bones") ? geometry.getAsJsonArray("bones") : new JsonArray();
        double[] anchor = anchorOf(bones);
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            scalePoint(bone, "pivot", anchor, factor);
            if (!bone.has("cubes")) continue;
            for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                JsonObject cube = cubeElement.getAsJsonObject();
                scalePoint(cube, "origin", anchor, factor);
                scalePoint(cube, "pivot", anchor, factor);
                if (!cube.has("size")) continue;
                JsonArray size = cube.getAsJsonArray("size");
                for (int i = 0; i < Math.min(3, size.size()); i++) {
                    size.set(i, new com.google.gson.JsonPrimitive(size.get(i).getAsDouble() * factor));
                }
            }
        }
        JsonObject description = geometry.has("description") ? geometry.getAsJsonObject("description") : null;
        if (description != null) {
            for (String key : new String[] {"visible_bounds_width", "visible_bounds_height"}) {
                if (description.has(key)) {
                    description.addProperty(key, description.get(key).getAsDouble() * factor);
                }
            }
        }
    }

    private static double[] anchorOf(JsonArray bones) {
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (bone.has("parent") && "body".equals(bone.get("parent").getAsString()) && bone.has("pivot")) {
                return vec3(bone, "pivot", new double[] {0, 24, 0});
            }
        }
        return new double[] {0, 24, 0};
    }

    private static void scalePoint(JsonObject owner, String key, double[] anchor, double factor) {
        if (!owner.has(key) || !owner.get(key).isJsonArray()) return;
        JsonArray array = owner.getAsJsonArray(key);
        for (int i = 0; i < Math.min(3, array.size()); i++) {
            double value = array.get(i).getAsDouble();
            array.set(i, new com.google.gson.JsonPrimitive(anchor[i] + (value - anchor[i]) * factor));
        }
    }

    /** Pinta bloques de color solido en la franja libre del atlas y devuelve su origen. */
    private static int[] paintDetailStrip(BufferedImage hd) {
        int y = hd.getHeight() - DETAIL_BLOCK;
        for (int i = 0; i < DETAIL_COLORS.length; i++) {
            int[] color = DETAIL_COLORS[i];
            int argb = 0xFF000000 | (color[0] << 16) | (color[1] << 8) | color[2];
            for (int dy = 0; dy < DETAIL_BLOCK; dy++) {
                for (int dx = 0; dx < DETAIL_BLOCK; dx++) {
                    hd.setRGB(i * DETAIL_BLOCK + dx, y + dy, argb);
                }
            }
        }
        return new int[] {0, y};
    }

    /**
     * El atlas declara 64 de ancho aunque el PNG tenga 256 o 512: las UV se normalizan por el
     * ancho declarado, asi que la franja de detalle se pasa a esa misma escala.
     */
    private static double[] detailUv(JsonObject geometry, int[] island, int imageW, int imageH) {
        JsonObject description = geometry.getAsJsonObject("description");
        double atlasW = description.get("texture_width").getAsDouble();
        double atlasH = description.get("texture_height").getAsDouble();
        double stepU = DETAIL_BLOCK * atlasW / Math.max(1, imageW);
        double sizeV = DETAIL_BLOCK * atlasH / Math.max(1, imageH);
        return new double[] {island[0] * atlasW / Math.max(1, imageW),
            island[1] * atlasH / Math.max(1, imageH), stepU, stepU, sizeV};
    }

    private static void decorateCompanion(JsonObject root, String slug, int[] island, int imageW, int imageH) {
        JsonObject geometry = geometryBody(root);
        double[] details = detailUv(geometry, island, imageW, imageH);
        JsonArray bones = geometry.has("bones") ? geometry.getAsJsonArray("bones") : new JsonArray();
        JsonArray headCubes = new JsonArray();
        JsonArray bodyCubes = new JsonArray();
        JsonArray legCubes = new JsonArray();

        JsonObject head = findBone(bones, "head");
        JsonObject torso = findBone(bones, "torso");
        if (torso == null) torso = findBone(bones, "box_base");

        if (head != null) {
            double[] box = cubeBox(head);
            double width = box[3] - box[0];
            double height = box[4] - box[1];
            double eyeSize = Math.max(0.32, width * 0.19);
            double eyeHeight = Math.max(0.28, height * 0.17);
            double eyeY = box[1] + height * 0.55;
            for (double side : new double[] {0.3, 0.7}) {
                headCubes.add(solidCube(box[0] + width * side - eyeSize / 2, eyeY, box[2] - 0.1,
                    eyeSize, eyeHeight, 0.14, DETAIL_BLACK, details));
            }
            if (slug.contains("reindeer") || slug.contains("elf")) {
                headCubes.add(solidCube(box[0] + width * 0.3 - eyeSize * 0.18, eyeY + eyeHeight * 0.45,
                    box[2] - 0.16, eyeSize * 0.38, eyeHeight * 0.38, 0.12, DETAIL_WHITE, details));
            }
            if (slug.contains("reindeer")) {
                // Cascabel dorado bajo el hocico.
                headCubes.add(solidCube(box[0] + width / 2 - 0.4, box[1] - 0.55, box[2] + 0.35,
                    0.8, 0.7, 0.7, DETAIL_GOLD, details));
            }
            if (slug.contains("elf")) {
                JsonObject tip = findBone(bones, "hat_tip");
                double[] hat = tip != null ? cubeBox(tip) : box;
                double puff = Math.max(0.8, (hat[3] - hat[0]) * 1.1);
                headCubes.add(solidCube(hat[0] + (hat[3] - hat[0]) / 2 - puff / 2, hat[4] - puff * 0.3,
                    hat[2] + (hat[5] - hat[2]) / 2 - puff / 2, puff, puff, puff, DETAIL_WHITE, details));
            }
        }

        if (torso != null) {
            double[] box = cubeBox(torso);
            double width = box[3] - box[0];
            double height = box[4] - box[1];
            if (slug.contains("snowman")) {
                for (int i = 0; i < 3; i++) {
                    double size = 0.36;
                    bodyCubes.add(solidCube(box[0] + width / 2 - size / 2,
                        box[1] + height * (0.22 + i * 0.2), box[2] - 0.08,
                        size, size, 0.16, DETAIL_BLACK, details));
                }
            }
            if (slug.contains("elf")) {
                bodyCubes.add(solidCube(box[0] + width / 2 - 0.42, box[1] + height * 0.28, box[2] - 0.1,
                    0.84, 0.6, 0.16, DETAIL_GOLD, details));
            }
            if (slug.contains("giftbox")) {
                bodyCubes.add(solidCube(box[0] + width / 2 - 0.4, box[1] + height * 0.55, box[2] - 0.1,
                    0.8, 1.0, 0.16, DETAIL_GOLD, details));
                bodyCubes.add(solidCube(box[0] + width / 2 - 0.24, box[1] + height * 0.3, box[2] - 0.1,
                    0.48, 0.7, 0.16, DETAIL_RED, details));
            }
        }

        if (slug.contains("reindeer")) {
            // Cascos oscuros al pie de cada pata.
            for (JsonElement element : bones) {
                JsonObject bone = element.getAsJsonObject();
                if (!bone.get("name").getAsString().startsWith("leg_")) continue;
                double[] box = cubeBox(bone);
                double width = Math.max(0.5, (box[3] - box[0]) * 0.95);
                double depth = Math.max(0.5, (box[5] - box[2]) * 0.95);
                legCubes.add(solidCube(box[0] + (box[3] - box[0]) / 2 - width / 2, box[1],
                    box[2] + (box[5] - box[2]) / 2 - depth / 2, width, 0.3, depth, DETAIL_BLACK, details));
            }
        }

        addDetailBone(bones, "detail_head", head != null ? head.get("name").getAsString() : "body", headCubes);
        addDetailBone(bones, "detail_body", torso != null ? torso.get("name").getAsString() : "body", bodyCubes);
        addDetailBone(bones, "detail_legs", torso != null ? torso.get("name").getAsString() : "body", legCubes);
    }

    private static void addDetailBone(JsonArray bones, String name, String parent, JsonArray cubes) {
        if (cubes.size() == 0) return;
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        bone.addProperty("parent", parent);
        bone.add("pivot", vec(0, 24, 0));
        bone.add("cubes", cubes);
        bones.add(bone);
    }

    private static JsonObject findBone(JsonArray bones, String token) {
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (!bone.has("cubes")) continue;
            if (bone.get("name").getAsString().toLowerCase().contains(token)) return bone;
        }
        return null;
    }

    /** Caja envolvente de los cubos de un hueso: {minX, minY, minZ, maxX, maxY, maxZ}. */
    private static double[] cubeBox(JsonObject bone) {
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
            JsonObject cube = cubeElement.getAsJsonObject();
            double[] origin = vec3(cube, "origin", new double[] {0, 0, 0});
            double[] size = vec3(cube, "size", new double[] {1, 1, 1});
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], origin[i]);
                max[i] = Math.max(max[i], origin[i] + size[i]);
            }
        }
        if (min[0] == Double.MAX_VALUE) return new double[] {0, 0, 0, 1, 1, 1};
        return new double[] {min[0], min[1], min[2], max[0], max[1], max[2]};
    }

    /** Cubo de un solo color: las seis caras apuntan al bloque de detalle del atlas. */
    private static JsonObject solidCube(double x, double y, double z, double sx, double sy, double sz,
                                        int block, double[] details) {
        JsonObject cube = new JsonObject();
        cube.add("origin", vec(x, y, z));
        cube.add("size", vec(sx, sy, sz));
        JsonObject faces = new JsonObject();
        for (String name : new String[] {"down", "up", "west", "north", "east", "south"}) {
            JsonObject face = new JsonObject();
            face.add("uv", vec(details[0] + block * details[2], details[1]));
            face.add("uv_size", vec(details[3], details[4]));
            faces.add(name, face);
        }
        cube.add("uv", faces);
        return cube;
    }

    // =====================================================================================
    // Geometria: aros y auras generados
    // =====================================================================================

    private static JsonObject buildGeometry(String shape, String identifier) {
        boolean aura = shape.startsWith("aura");
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        JsonArray geometries = new JsonArray();
        JsonObject geometry = new JsonObject();

        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry." + identifier);
        description.addProperty("texture_width", 256);
        description.addProperty("texture_height", 256);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4);
        description.add("visible_bounds_offset", vec(0, 1.5, 0));
        geometry.add("description", description);

        JsonObject bone = new JsonObject();
        bone.addProperty("name", "cosmetic");
        bone.addProperty("parent", aura ? "body" : "head");
        bone.add("pivot", vec(0, 24, 0));

        JsonArray cubes = new JsonArray();
        int[] patch = {0};
        switch (shape) {
            case "ring_thin" -> torus(cubes, 5.0, 34.6, 0.55, 0.55, 36, patch);
            case "ring_double" -> {
                torus(cubes, 4.1, 34.0, 0.45, 0.45, 32, patch);
                torus(cubes, 5.9, 35.6, 0.45, 0.45, 36, patch);
            }
            case "crown_spikes" -> {
                torus(cubes, 4.6, 34.8, 0.8, 0.7, 28, patch);
                for (int i = 0; i < 8; i++) {
                    double angle = 2 * Math.PI * i / 8;
                    double height = 2.0 + (i % 3);
                    addCube(cubes, Math.cos(angle) * 4.6, 35.0, Math.sin(angle) * 4.6,
                        0.9, height, 0.9, 0, -angle, patch);
                }
            }
            case "orbit_orbs" -> {
                torus(cubes, 5.4, 35.0, 0.28, 0.28, 30, patch);
                for (int i = 0; i < 4; i++) {
                    double angle = 2 * Math.PI * i / 4 + 0.4;
                    addCube(cubes, Math.cos(angle) * 5.4, 35.0, Math.sin(angle) * 5.4,
                        1.1, 1.1, 1.1, 0, 0, patch);
                }
            }
            case "aura_ring" -> {
                torus(cubes, 9.5, 1.2, 0.5, 0.5, 40, patch);
                torus(cubes, 9.5, 2.4, 0.28, 0.28, 40, patch);
            }
            case "aura_flames" -> {
                for (int i = 0; i < 18; i++) {
                    double angle = 2 * Math.PI * i / 18;
                    double height = 2.0 + 2.6 * Math.abs(Math.sin(i * 1.7));
                    addCube(cubes, Math.cos(angle) * 9.0, height * 0.5, Math.sin(angle) * 9.0,
                        0.7, height, 0.7, 0, -angle, patch);
                }
            }
            case "aura_runes" -> {
                torus(cubes, 9.2, 0.3, 0.22, 0.22, 44, patch);
                for (int i = 0; i < 14; i++) {
                    double angle = 2 * Math.PI * i / 14;
                    addCube(cubes, Math.cos(angle) * 9.2, 0.9, Math.sin(angle) * 9.2,
                        1.3, 1.3, 1.3, 0, 0, patch);
                }
            }
            case "aura_shards" -> {
                for (int i = 0; i < 8; i++) {
                    double angle = 2 * Math.PI * i / 8 + 0.2;
                    double height = 1.6 + (i % 4) * 1.3;
                    addCube(cubes, Math.cos(angle) * 8.2, height * 0.5, Math.sin(angle) * 8.2,
                        0.9, height, 0.9, 0, -angle, patch);
                }
            }
            default -> throw new IllegalArgumentException("geometria desconocida: " + shape);
        }

        bone.add("cubes", cubes);
        JsonArray bones = new JsonArray();
        bones.add(bone);
        geometry.add("bones", bones);
        geometries.add(geometry);
        root.add("minecraft:geometry", geometries);
        return root;
    }

    private static void torus(JsonArray cubes, double radius, double y, double thick, double depth,
                              int segments, int[] patch) {
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            addCube(cubes, x, y, z, thick, thick, depth, 0, -angle, patch);
        }
    }

    /**
     * Los cubos son casi cuadrados (0,5-1,1) a proposito: sin rotacion por eje no hay forma de
     * equivocar el signo del giro y el aro se ve igual de limpio.
     */
    private static void addCube(JsonArray cubes, double x, double y, double z, double sx, double sy, double sz,
                                int extraRotation, double angle, int[] patch) {
        JsonObject cube = new JsonObject();
        cube.add("origin", vec(x - sx / 2, y - sy / 2, z - sz / 2));
        cube.add("size", vec(sx, sy, sz));

        // Cada cubo toma una franja distinta del atlas: con rampas horizontales el aro recorre
        // los colores en vez de quedar de un solo tono.
        int u = (patch[0] * 16) % 240;
        patch[0]++;
        JsonObject uv = new JsonObject();
        for (String name : new String[] {"down", "up", "west", "north", "east", "south"}) {
            JsonObject face = new JsonObject();
            face.add("uv", vec(u, 16));
            face.add("uv_size", vec(16, 16));
            uv.add(name, face);
        }
        cube.add("uv", uv);
        cubes.add(cube);
    }

    private static JsonArray vec(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) array.add(value);
        return array;
    }

    // =====================================================================================
    // Arte
    // =====================================================================================

    private static BufferedImage upscale(BufferedImage source, int factor) {
        if (factor <= 1) return source;
        BufferedImage out = new BufferedImage(source.getWidth() * factor, source.getHeight() * factor,
            BufferedImage.TYPE_INT_ARGB);
        AffineTransformOp op = new AffineTransformOp(AffineTransform.getScaleInstance(factor, factor),
            new RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC));
        op.filter(source, out);
        return out;
    }

    /**
     * Materiales sobre la textura original del modelo (conserva el layout UV que ya conoce el
     * cliente) anadiendo un acabado distinto en cada uno.
     */
    private static BufferedImage applyMaterial(BufferedImage source, String material, int variant, String baseSlug) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        // Duotono por luminancia: cada material tiene su propia rampa, asi que el mismo modelo
        // cambia de material de verdad (no solo de tono) sin perder el dibujo de las UV.
        int[] lut = switch (material) {
            case "ember" -> ramp(0x140300, 0x8a2400, 0xff7a1a, 0xffe6a8);
            case "frost" -> ramp(0x061626, 0x2b6f9e, 0xa5e4ff, 0xffffff);
            default -> ramp(0x140a2a, 0x6a4bd0, 0x2ad7ff, 0xffffff);
        };
        float[] hsb = new float[3];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
                // La saturacion pesa un poco para que un color plano no se vuelva gris.
                float level = clamp(hsb[2] * 0.82f + hsb[1] * 0.18f);
                int rgb = lut[Math.min(255, (int) (level * 255))];
                out.setRGB(x, y, (alpha << 24) | (rgb & 0x00FFFFFF));
            }
        }

        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // La semilla lleva el modelo: dos modelos con el mismo atlas de origen (las dos renas del
        // jar comparten dibujo) no acaban con la misma textura.
        Random random = new Random(1337L + variant * 91L + baseSlug.hashCode());

        // Grano fino por modelo, siempre visible como material y no como ruido suelto.
        for (int i = 0; i < width * height / 5; i++) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            int argb = out.getRGB(x, y);
            if (((argb >>> 24) & 0xFF) == 0) continue;
            int delta = random.nextInt(30) - 15;
            int r = Math.max(0, Math.min(255, ((argb >> 16) & 0xFF) + delta));
            int gg = Math.max(0, Math.min(255, ((argb >> 8) & 0xFF) + delta));
            int b = Math.max(0, Math.min(255, (argb & 0xFF) + delta));
            out.setRGB(x, y, (argb & 0xFF000000) | (r << 16) | (gg << 8) | b);
        }

        switch (patternFor(baseSlug)) {
            case "rings" -> {
                g.setColor(new Color(255, 255, 255, 34));
                for (int ring = 0; ring < 4; ring++) {
                    g.drawOval(width / 2 - 30 - ring * 22, height / 2 - 30 - ring * 22,
                        60 + ring * 44, 60 + ring * 44);
                }
            }
            case "ribbon" -> {
                g.setColor(new Color(255, 255, 255, 70));
                g.setStroke(new BasicStroke(6f));
                g.drawLine(0, height / 2, width, height / 2);
                g.drawLine(width / 2, 0, width / 2, height);
            }
            default -> { }
        }
        switch (material) {
            case "prism" -> {
                // Barrido espectral en diagonal + purpurina.
                for (int i = -height; i < width; i += 3) {
                    int hue = (i * 4) & 0xFF;
                    int rgb = Color.HSBtoRGB(hue / 255f, 0.85f, 1f);
                    g.setColor(new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 26));
                    g.drawLine(i, 0, i + height, height);
                }
                for (int i = 0; i < 160; i++) {
                    g.setColor(new Color(255, 255, 255, 90 + random.nextInt(90)));
                    g.fillOval(random.nextInt(width), random.nextInt(height), 1 + random.nextInt(2), 1 + random.nextInt(2));
                }
            }
            case "ember" -> {
                // Grano oscuro y ascuas que suben.
                for (int i = 0; i < 900; i++) {
                    g.setColor(new Color(20, 6, 0, 40 + random.nextInt(50)));
                    g.fillOval(random.nextInt(width), random.nextInt(height), 1, 1);
                }
                for (int i = 0; i < 220; i++) {
                    int x = random.nextInt(width);
                    int y = height - random.nextInt(height);
                    int size = 1 + random.nextInt(2);
                    g.setColor(new Color(255, 120 + random.nextInt(90), 40, 70 + random.nextInt(120)));
                    g.fillOval(x, y, size, size);
                }
            }
            default -> {
                // Facetas cristalinas y escarcha.
                for (int i = 0; i < 70; i++) {
                    int x = random.nextInt(width);
                    int y = random.nextInt(height);
                    int size = 6 + random.nextInt(14);
                    Path2D path = new Path2D.Double();
                    path.moveTo(x, y);
                    path.lineTo(x + size, y + size / 3);
                    path.lineTo(x + size / 2, y + size);
                    path.closePath();
                    g.setColor(new Color(190, 235, 255, 30 + random.nextInt(40)));
                    g.fill(path);
                    g.setColor(new Color(235, 250, 255, 60));
                    g.draw(path);
                }
                for (int i = 0; i < 240; i++) {
                    g.setColor(new Color(255, 255, 255, 60 + random.nextInt(90)));
                    g.fillOval(random.nextInt(width), random.nextInt(height), 1, 1);
                }
            }
        }
        g.dispose();
        return out;
    }

    private static String patternFor(String baseSlug) {
        return switch (baseSlug) {
            case "shoulder_reindeer", "pet_reindeer" -> "rings";
            case "pet_giftbox" -> "ribbon";
            default -> "none";
        };
    }

    // ---------------------------------------------------------------------------------------
    // Escenas de capa: cada tema dibuja un motivo distinto, no un campo de estrellas retintado.
    // ---------------------------------------------------------------------------------------

    private static void drawStarfield(Graphics2D g, int width, int height, Random random, int count) {
        for (int i = 0; i < count; i++) {
            int alpha = 40 + random.nextInt(170);
            g.setColor(new Color(255, 255, 255, alpha));
            int size = random.nextInt(11) == 0 ? 3 : 1;
            g.fillOval(random.nextInt(width), random.nextInt(height), size, size);
        }
    }

    /** Planeta gigante con bandas, anillo cruzandolo y dos lunas. */
    private static void sceneRingedPlanet(Graphics2D g, int width, int height, Random random) {
        drawStarfield(g, width, height, random, 230);
        int cx = width / 2;
        int cy = (int) (height * 0.55);
        int rad = (int) (height * 0.4);
        java.awt.geom.Ellipse2D ring = new java.awt.geom.Ellipse2D.Double(
            cx - rad * 1.7, cy - rad * 0.32, rad * 3.4, rad * 0.64);

        g.setStroke(new BasicStroke(Math.max(5f, rad * 0.08f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(226, 198, 148, 120));
        g.draw(ring);
        g.setStroke(new BasicStroke(Math.max(2f, rad * 0.03f)));
        g.setColor(new Color(255, 238, 205, 80));
        g.draw(new java.awt.geom.Ellipse2D.Double(cx - rad * 1.9, cy - rad * 0.36, rad * 3.8, rad * 0.72));

        java.awt.Shape clipBefore = g.getClip();
        g.setClip(new java.awt.geom.Ellipse2D.Double(cx - rad, cy - rad, rad * 2.0, rad * 2.0));
        Color[] bands = {
            new Color(184, 122, 68), new Color(230, 182, 129), new Color(141, 83, 48),
            new Color(243, 221, 180), new Color(196, 128, 74),
        };
        int y = cy - rad;
        for (int i = 0; y < cy + rad; i++) {
            int band = 7 + (i % 4) * 5;
            g.setColor(bands[i % bands.length]);
            g.fillRect(cx - rad, y, rad * 2, band);
            y += band;
        }
        // Noche y brillo cenital: le da volumen de esfera.
        g.setPaint(new java.awt.GradientPaint((float) (cx - rad), 0f, new Color(0, 0, 0, 0),
            (float) (cx + rad), 0f, new Color(3, 3, 12, 225)));
        g.fillRect(cx - rad, cy - rad, rad * 2, rad * 2);
        g.setPaint(new java.awt.GradientPaint(0f, (float) (cy - rad), new Color(255, 250, 225, 80),
            0f, (float) (cy + rad), new Color(255, 250, 225, 0)));
        g.fillRect(cx - rad, cy - rad, rad * 2, rad * 2);
        g.setClip(clipBefore);

        // Mitad cercana del anillo, delante del planeta.
        g.setClip(new java.awt.Rectangle(0, cy + (int) (rad * 0.04), width, height));
        g.setStroke(new BasicStroke(Math.max(5f, rad * 0.08f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(226, 198, 148, 180));
        g.draw(ring);
        g.setClip(clipBefore);

        g.setColor(new Color(214, 220, 235));
        g.fillOval((int) (cx + rad * 1.12), (int) (cy - rad * 0.98), rad / 5, rad / 5);
        g.setColor(new Color(176, 186, 206));
        g.fillOval((int) (cx - rad * 1.42), (int) (cy + rad * 0.6), rad / 7, rad / 7);
    }

    /** Cometa: nucleo brillante y cola curva de gas y polvo. */
    private static void sceneComet(Graphics2D g, int width, int height, double phase, Random random) {
        drawStarfield(g, width, height, random, 200);
        double headX = width * 0.26;
        double headY = height * 0.3;
        double wobble = Math.sin(phase * Math.PI * 2) * height * 0.03;

        // Dos colas: la ionica azul (recta y fina) y la de polvo (ancha, calida y desviada).
        for (int pass = 0; pass < 2; pass++) {
            boolean dust = pass == 1;
            for (int i = 0; i < 80; i++) {
                double f = i / 79.0;
                double endX = headX + width * (dust ? 0.66 : 0.82) * f;
                double endY = headY + height * (dust ? 0.9 : 0.78) * f
                    + Math.sin(f * 5.0 + phase * 6.28 + pass * 1.7) * (dust ? 22 : 10) + wobble;
                double jitter = (random.nextDouble() - 0.5) * (dust ? 8 : 3);
                g.setStroke(new BasicStroke((float) ((dust ? 13 : 15) * (1 - f)) + 1f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(dust
                    ? new Color(255, 226, 190, (int) (60 * (1 - f)))
                    : new Color(150, 220, 255, (int) (85 * (1 - f))));
                g.draw(new java.awt.geom.Line2D.Double(
                    headX + jitter, headY + jitter, endX + jitter, endY + jitter));
                if (!dust) {
                    g.setStroke(new BasicStroke((float) (5 * (1 - f)) + 1f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(new Color(255, 250, 235, (int) (110 * (1 - f))));
                    g.draw(new java.awt.geom.Line2D.Double(headX, headY, endX, endY));
                }
            }
        }
        // Halos del nucleo, de fuera hacia dentro.
        for (int i = 14; i >= 0; i--) {
            int size = 8 + i * 5;
            g.setColor(new Color(180, 240, 255, 8 + i * 2));
            g.fillOval((int) headX - size / 2, (int) headY - size / 2, size, size);
        }
        g.setColor(new Color(255, 255, 255, 250));
        g.fillOval((int) headX - 4, (int) headY - 4, 8, 8);
    }

    /** Constelacion: estrellas unidas por lineas sobre retícula de carta estelar. */
    private static void sceneConstellation(Graphics2D g, int width, int height, double phase, Random random) {
        drawStarfield(g, width, height, random, 170);
        g.setColor(new Color(120, 180, 255, 26));
        for (int i = 1; i < 6; i++) {
            g.drawLine(width * i / 6, 0, width * i / 6, height);
        }
        for (int i = 1; i < 4; i++) {
            g.drawLine(0, height * i / 4, width, height * i / 4);
        }

        double[][] stars = {
            {0.14, 0.68}, {0.28, 0.42}, {0.41, 0.60}, {0.55, 0.32}, {0.68, 0.54}, {0.82, 0.28},
        };
        g.setStroke(new BasicStroke(1.6f));
        g.setColor(new Color(150, 200, 255, 120));
        for (int i = 0; i + 1 < stars.length; i++) {
            g.draw(new java.awt.geom.Line2D.Double(
                stars[i][0] * width, stars[i][1] * height, stars[i + 1][0] * width, stars[i + 1][1] * height));
        }
        for (int i = 0; i < stars.length; i++) {
            double pulse = 0.55 + 0.45 * Math.sin(phase * Math.PI * 2 + i * 1.1);
            int x = (int) (stars[i][0] * width);
            int y = (int) (stars[i][1] * height);
            int size = (int) (5 + 9 * pulse);
            g.setColor(new Color(150, 205, 255, (int) (45 + 90 * pulse)));
            g.fillOval(x - size, y - size, size * 2, size * 2);
            g.setColor(new Color(255, 255, 255, (int) (150 + 100 * pulse)));
            g.fillOval(x - size / 3, y - size / 3, size / 1.5 > 1 ? (int) (size / 1.5) : 2, size / 1.5 > 1 ? (int) (size / 1.5) : 2);
        }
    }

    /** Agujero negro: horizonte oscuro, disco de acrecion girando y anillos de lente. */
    private static void sceneBlackHole(Graphics2D g, int width, int height, double phase, Random random) {
        drawStarfield(g, width, height, random, 210);
        int cx = width / 2;
        int cy = (int) (height * 0.5);
        int rad = (int) (height * 0.26);

        for (int i = 14; i >= 0; i--) {
            double f = i / 14.0;
            double rx = rad * (1.45 + f * 2.3);
            double ry = rad * (0.3 + f * 0.5);
            int alpha = (int) (25 + 120 * (1 - f));
            int rgb = Color.HSBtoRGB((float) ((0.02 + 0.09 * (1 - f) + phase * 0.03) % 1.0), 0.92f, 1f);
            g.setStroke(new BasicStroke(2f + (float) (7 * (1 - f))));
            g.setColor(new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, Math.min(230, alpha)));
            g.draw(new java.awt.geom.Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2));
        }
        // Halo por delante del disco.
        for (int i = 16; i >= 0; i--) {
            int size = rad * 2 + i * 6;
            g.setColor(new Color(255, 190, 120, 5));
            g.fillOval(cx - size / 2, cy - size / 2, size, size);
        }
        g.setColor(new Color(2, 2, 6, 255));
        g.fillOval(cx - rad, cy - rad, rad * 2, rad * 2);
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(255, 220, 170, 210));
        g.draw(new java.awt.geom.Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
    }

    /** Pilares de polvo de nebulosa, cada columna con su propio color y grosor. */
    private static void scenePillars(Graphics2D g, int width, int height, Random random) {
        drawStarfield(g, width, height, random, 250);
        for (int col = 0; col < 4; col++) {
            double baseX = width * (0.16 + col * 0.22);
            double wide = width * (0.055 + 0.018 * ((col * 5) % 3));
            for (int step = 0; step < 110; step++) {
                double f = step / 109.0;
                double y = height * (1.0 - f);
                double sway = Math.sin(f * 4.2 + col * 1.7) * wide * 0.7;
                int alpha = (int) Math.min(200, 30 + 170 * Math.pow(1 - f, 0.55) * (0.6 + 0.4 * Math.sin(f * 8 + col)));
                int rgb = Color.HSBtoRGB((float) ((0.74 + 0.13 * Math.sin(f * 3 + col)) % 1.0), 0.62f, 0.95f);
                g.setColor(new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha));
                g.fillOval((int) (baseX + sway - wide / 2), (int) y, (int) wide, (int) (height * 0.035));
            }
        }
    }

    /** Aurora: cortinas de luz sobre un horizonte de colinas en silueta. */
    private static void sceneAurora(Graphics2D g, int width, int height, double phase, Random random) {
        drawStarfield(g, width, height, random, 150);
        for (int band = 0; band < 6; band++) {
            double base = height * (0.16 + band * 0.05);
            Path2D curtain = new Path2D.Double();
            for (int x = 0; x <= width; x += 4) {
                double y = base + Math.sin(x * 0.011 + band * 1.7 + phase * 6.28) * height * 0.07;
                if (x == 0) curtain.moveTo(x, y); else curtain.lineTo(x, y);
            }
            Path2D fill = new Path2D.Double(curtain);
            fill.lineTo(width, height * 0.72);
            fill.lineTo(0, height * 0.72);
            fill.closePath();
            int hue = band % 2 == 0 ? 0 : 1;
            g.setPaint(new java.awt.GradientPaint(0f, (float) base,
                hue == 0 ? new Color(70, 255, 190, 95) : new Color(150, 120, 255, 85),
                0f, (float) (height * 0.72), new Color(40, 90, 220, 12)));
            g.fill(fill);
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(180, 255, 235, 110));
            g.draw(curtain);
        }
        Path2D hills = new Path2D.Double();
        hills.moveTo(0, height);
        for (int x = 0; x <= width; x += 6) {
            hills.lineTo(x, height * 0.87 + Math.sin(x * 0.0055) * height * 0.05 + Math.sin(x * 0.02) * height * 0.018);
        }
        hills.lineTo(width, height);
        hills.closePath();
        g.setColor(new Color(4, 10, 16, 245));
        g.fill(hills);
    }

    /** Dos planetas vecinos con luna: el grande corta el borde de la capa. */
    private static void scenePlanets(Graphics2D g, int width, int height, Random random) {
        drawStarfield(g, width, height, random, 190);
        int bigRad = (int) (height * 0.62);
        int bcx = (int) (-width * 0.02);
        int bcy = (int) (height * 0.92);
        g.setColor(new Color(66, 128, 196));
        g.fillOval(bcx - bigRad, bcy - bigRad, bigRad * 2, bigRad * 2);
        for (int i = 0; i < 5; i++) {
            g.setColor(new Color(96, 170, 226, 70));
            g.fillOval(bcx - bigRad + 4, bcy - bigRad + 8 + i * 14, bigRad * 2 - 8, 6 + i);
        }
        for (int i = 12; i >= 0; i--) {
            g.setColor(new Color(120, 200, 255, 4));
            int size = bigRad * 2 + i * 5;
            g.fillOval(bcx - size / 2, bcy - size / 2, size, size);
        }

        int smallRad = (int) (height * 0.17);
        int scx = (int) (width * 0.76);
        int scy = (int) (height * 0.3);
        g.setColor(new Color(206, 142, 92));
        g.fillOval(scx - smallRad, scy - smallRad, smallRad * 2, smallRad * 2);
        g.setPaint(new java.awt.GradientPaint((float) (scx - smallRad), 0f, new Color(0, 0, 0, 0),
            (float) (scx + smallRad), 0f, new Color(4, 4, 14, 200)));
        g.fillOval(scx - smallRad, scy - smallRad, smallRad * 2, smallRad * 2);
        g.setColor(new Color(226, 230, 240, 220));
        g.fillOval(scx + smallRad + 6, scy + (int) (smallRad * 0.7), smallRad / 3, smallRad / 3);
    }

    private record Theme(int[] ramp, String gradient, float noise, String deco, float density, int seed) {}

    private static Theme theme(String name) {
        return switch (name) {
            case "rainbow" -> new Theme(spectrum(), "horizontal", 0.05f, "sparkle", 0.4f, 11);
            case "solar" -> new Theme(ramp(0x2b0d00, 0xff6a00, 0xffd24a, 0xfff4c2), "radial", 0.15f, "rays", 0.6f, 12);
            case "aurora" -> new Theme(ramp(0x030b18, 0x0b6b6b, 0x36d6a0, 0x9d7bff, 0x03121a), "vertical", 0.18f, "aurora", 0.7f, 13);
            case "amethyst" -> new Theme(ramp(0x120022, 0x4b1f7a, 0xa26bff, 0xe2ccff), "radial", 0.25f, "sparkle", 0.4f, 14);
            case "gold" -> new Theme(ramp(0x2a1a00, 0x8a5a12, 0xe8b93a, 0xfff0b0), "vertical", 0.12f, "sparkle", 0.5f, 15);
            case "obsidian" -> new Theme(ramp(0x05040a, 0x141126, 0x35305c, 0x8c7dff), "diagonal", 0.22f, "cracks", 0.5f, 16);
            case "nebula" -> new Theme(ramp(0x05010d, 0x2a0f5a, 0x7a2ea8, 0x2ad7ff), "radial", 0.55f, "stars", 0.6f, 17);
            case "ember" -> new Theme(ramp(0x0a0400, 0x5a1400, 0xd63a00, 0xffc44a), "vertical", 0.45f, "sparks", 0.8f, 18);
            case "emerald" -> new Theme(ramp(0x00120a, 0x0a4a2a, 0x2fd07a, 0xd8ffe8), "radial", 0.25f, "sparkle", 0.4f, 19);
            case "toxic" -> new Theme(ramp(0x0a1200, 0x2f4a00, 0x9ee000, 0xf0ff9a), "diagonal", 0.4f, "sparks", 0.6f, 20);
            case "arcane" -> new Theme(ramp(0x0a0018, 0x2a0a5a, 0x7a48ff, 0x9ff6ff), "vertical", 0.3f, "runes", 0.7f, 21);
            case "void" -> new Theme(ramp(0x020006, 0x0a0018, 0x2a0a4a, 0x6a3aff), "radial", 0.3f, "runes", 0.8f, 22);
            case "frost" -> new Theme(ramp(0x081a2b, 0x2b6f9e, 0x8fd8ff, 0xf2ffff), "vertical", 0.2f, "facets", 0.7f, 23);
            case "sakura" -> new Theme(ramp(0x2a0a18, 0xa84070, 0xff9ec4, 0xffe6f0), "radial", 0.2f, "petals", 0.7f, 24);
            case "galaxy" -> new Theme(ramp(0x03010a, 0x1b0b38, 0x6a1b9a, 0xab47bc, 0x00e5ff), "radial", 0.6f, "stars", 0.9f, 25);
            case "cyber" -> new Theme(ramp(0x02060c, 0x07223a, 0x00b0ff, 0xff007f), "diagonal", 0.15f, "grid", 0.7f, 26);
            case "starfall" -> new Theme(ramp(0x01030f, 0x0a1a4a, 0x2a4aff, 0xbfe4ff), "vertical", 0.4f, "stars", 1.0f, 27);
            case "hex" -> new Theme(ramp(0x04121a, 0x0a4152, 0x22c4d8, 0xd0fbff), "radial", 0.2f, "hex", 0.8f, 28);
            case "cloud" -> new Theme(ramp(0x0a0a1a, 0x4a3a7a, 0xb890e0, 0xffe6ff), "vertical", 0.7f, "sparkle", 0.3f, 29);
            case "crest" -> new Theme(ramp(0x0a0206, 0x4a0a1a, 0xa01a2a, 0xffd24a), "radial", 0.15f, "crest", 0.9f, 30);
            case "circuit" -> new Theme(ramp(0x020c0a, 0x0a3a32, 0x1ad0a0, 0xc8fff0), "diagonal", 0.12f, "traces", 0.7f, 31);
            case "holo" -> new Theme(spectrum(), "horizontal", 0.1f, "scanlines", 0.8f, 32);
            case "huewheel" -> new Theme(spectrum(), "radial", 0.06f, "sparkle", 0.5f, 34);
            case "aurora_drift" -> new Theme(ramp(0x02060f, 0x0a4a5a, 0x2ad0b0, 0x8a5cff, 0x04121a), "vertical", 0.2f, "aurora", 0.9f, 35);
            case "ringed" -> new Theme(ramp(0x02030a, 0x0a0f2a, 0x141a3a), "vertical", 0.18f, "ringed", 0.7f, 45);
            case "comet" -> new Theme(ramp(0x01020a, 0x081030, 0x141a48), "vertical", 0.2f, "comet", 0.8f, 46);
            case "constellation" -> new Theme(ramp(0x01030c, 0x081034, 0x10204a), "vertical", 0.18f, "constellation", 0.9f, 47);
            case "blackhole" -> new Theme(ramp(0x020106, 0x0a0418, 0x180a30), "radial", 0.25f, "blackhole", 0.9f, 48);
            case "pillars" -> new Theme(ramp(0x05010d, 0x1a0a3a, 0x4a1a6a), "vertical", 0.35f, "pillars", 0.9f, 49);
            case "planets" -> new Theme(ramp(0x02040c, 0x0a1024, 0x141c3c), "radial", 0.2f, "planets", 0.8f, 50);
            case "ember_flow" -> new Theme(ramp(0x0a0200, 0x4a0f00, 0xc23000, 0xffa62a), "diagonal", 0.5f, "sparks", 0.9f, 36);
            case "star_twinkle" -> new Theme(ramp(0x01020a, 0x0a1440, 0x2a3ac8, 0xdfefff), "radial", 0.45f, "stars", 1.0f, 37);
            case "holo_shift" -> new Theme(spectrum(), "diagonal", 0.12f, "scanlines", 0.6f, 38);
            case "prismglass" -> new Theme(spectrum(), "vertical", 0.12f, "facets", 0.8f, 39);
            case "deepsea" -> new Theme(ramp(0x001018, 0x0a4a6a, 0x2ad0ff, 0xe6ffff), "radial", 0.3f, "traces", 0.6f, 40);
            case "radiance" -> new Theme(ramp(0x2a1a00, 0xffd24a, 0xfffbe0), "radial", 0.1f, "rays", 0.7f, 41);
            case "monochrome" -> new Theme(ramp(0x08080c, 0x3a3a48, 0x9a9ab0, 0xf0f0ff), "diagonal", 0.2f, "cracks", 0.6f, 42);
            case "petalstorm" -> new Theme(ramp(0x1a0510, 0x7a2050, 0xff8ac0, 0xffe0f0), "diagonal", 0.25f, "petals", 0.9f, 43);
            case "lava" -> new Theme(ramp(0x0a0000, 0x6a0a00, 0xff5a00, 0xffd24a), "vertical", 0.5f, "cracks", 0.9f, 44);
            default -> new Theme(ramp(0x101010, 0x404040, 0xc0c0c0), "vertical", 0.2f, "sparkle", 0.3f, 33);
        };
    }

    private static BufferedImage render(String themeName, int width, int height, double phase) {
        Theme theme = theme(themeName);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double u = x / (double) (width - 1);
                double v = y / (double) (height - 1);

                double base = switch (theme.gradient()) {
                    case "horizontal" -> u;
                    case "diagonal" -> (u + v) * 0.5;
                    case "radial" -> {
                        double dx = u - 0.5;
                        double dy = v - 0.5;
                        yield clamp((float) (1.0 - Math.sqrt(dx * dx + dy * dy) * 1.6));
                    }
                    default -> v;
                };

                if (theme.noise() > 0) {
                    float noise = fbm((float) (u * 6 + phase * 4), (float) (v * 6 - phase * 2), theme.seed(), 4);
                    base = base * (1 - theme.noise()) + noise * theme.noise();
                }

                int color = theme.ramp()[Math.min(255, Math.max(0, (int) (clamp((float) base) * 255)))];
                image.setRGB(x, y, color);
            }
        }

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1f));
        Random random = new Random(theme.seed() * 31L + 7);

        switch (theme.deco()) {
            case "ringed" -> sceneRingedPlanet(g, width, height, random);
            case "comet" -> sceneComet(g, width, height, phase, random);
            case "constellation" -> sceneConstellation(g, width, height, phase, random);
            case "blackhole" -> sceneBlackHole(g, width, height, phase, random);
            case "pillars" -> scenePillars(g, width, height, random);
            case "aurora" -> sceneAurora(g, width, height, phase, random);
            case "planets" -> scenePlanets(g, width, height, random);
            case "stars" -> {
                int count = (int) (120 * theme.density());
                for (int i = 0; i < count; i++) {
                    int alpha = 90 + random.nextInt(160);
                    double twinkle = 0.6 + 0.4 * Math.sin(phase * 2 * Math.PI + i);
                    g.setColor(new Color(255, 255, 255, (int) Math.min(255, alpha * twinkle)));
                    int size = random.nextInt(10) == 0 ? 3 : 1;
                    g.fillOval(random.nextInt(width), random.nextInt(height), size, size);
                }
            }
            case "sparkle" -> {
                for (int i = 0; i < 200 * theme.density(); i++) {
                    g.setColor(new Color(255, 255, 255, 40 + random.nextInt(120)));
                    g.fillOval(random.nextInt(width), random.nextInt(height), 1 + random.nextInt(2), 1 + random.nextInt(2));
                }
            }
            case "ribbons" -> {
                for (int band = 0; band < 5; band++) {
                    Path2D path = new Path2D.Double();
                    double offset = band * 0.18 + phase * 0.6;
                    for (int x = 0; x <= width; x += 4) {
                        double y = height * (0.25 + offset % 0.5) + Math.sin(x * 0.012 + band * 1.3 + phase * 6.28) * 26;
                        if (x == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    g.setColor(new Color(160, 255, 230, 34 + band * 10));
                    g.setStroke(new BasicStroke(10f - band, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(path);
                }
            }
            case "rays" -> {
                g.setColor(new Color(255, 240, 190, 70));
                for (int i = 0; i < 28; i++) {
                    double angle = 2 * Math.PI * i / 28 + phase;
                    g.drawLine(width / 2, height / 2,
                        (int) (width / 2 + Math.cos(angle) * width), (int) (height / 2 + Math.sin(angle) * height));
                }
            }
            case "sparks" -> {
                for (int i = 0; i < 260 * theme.density(); i++) {
                    int x = random.nextInt(width);
                    int y = height - (int) (random.nextDouble() * random.nextDouble() * height);
                    g.setColor(new Color(255, 140 + random.nextInt(90), 40, 80 + random.nextInt(140)));
                    g.fillOval(x, (int) ((y + phase * height * 0.15) % height), 1 + random.nextInt(2), 1 + random.nextInt(3));
                }
            }
            case "runes" -> {
                g.setColor(new Color(190, 240, 255, 90));
                for (int row = 0; row < 6; row++) {
                    int y = (int) (height * (0.15 + row * 0.14));
                    for (int col = 0; col < 10; col++) {
                        int x = (int) (width * (0.06 + col * 0.095));
                        int size = 6 + random.nextInt(6);
                        g.drawLine(x, y, x + size, y);
                        g.drawLine(x + size / 2, y - size / 2, x + size / 2, y + size / 2);
                        if (random.nextBoolean()) g.drawLine(x, y + size / 2, x + size, y + size / 2);
                    }
                }
            }
            case "facets" -> {
                for (int i = 0; i < 90 * theme.density(); i++) {
                    int x = random.nextInt(width);
                    int y = random.nextInt(height);
                    int size = 12 + random.nextInt(26);
                    Path2D path = new Path2D.Double();
                    path.moveTo(x, y);
                    path.lineTo(x + size, y + size / 3);
                    path.lineTo(x + size / 2, y + size);
                    path.closePath();
                    g.setColor(new Color(220, 245, 255, 26 + random.nextInt(40)));
                    g.fill(path);
                    g.setColor(new Color(255, 255, 255, 55));
                    g.draw(path);
                }
            }
            case "petals" -> {
                for (int i = 0; i < 70; i++) {
                    int x = random.nextInt(width);
                    int y = random.nextInt(height);
                    int size = 4 + random.nextInt(6);
                    g.setColor(new Color(255, 190, 220, 70 + random.nextInt(90)));
                    g.fill(new Ellipse2D.Double(x, y, size, size * 0.6));
                }
            }
            case "grid" -> {
                for (int x = 0; x < width; x += 16) {
                    g.setColor(new Color(0, 220, 255, 40));
                    g.drawLine(x, 0, x, height);
                }
                for (int y = 0; y < height; y += 16) {
                    g.setColor(new Color(0, 220, 255, 40));
                    g.drawLine(0, y, width, y);
                }
            }
            case "hex" -> {
                g.setColor(new Color(190, 250, 255, 60));
                double radius = 18;
                for (int row = -1; row < height / (radius * 1.5) + 1; row++) {
                    for (int col = -1; col < width / (radius * 1.74) + 1; col++) {
                        double cx = col * radius * 1.74 + (row % 2 == 0 ? 0 : radius * 0.87);
                        double cy = row * radius * 1.5;
                        Path2D hex = new Path2D.Double();
                        for (int k = 0; k < 6; k++) {
                            double angle = Math.PI / 3 * k;
                            double px = cx + Math.cos(angle) * radius * 0.9;
                            double py = cy + Math.sin(angle) * radius * 0.9;
                            if (k == 0) hex.moveTo(px, py); else hex.lineTo(px, py);
                        }
                        hex.closePath();
                        g.draw(hex);
                    }
                }
            }
            case "crest" -> {
                Path2D shield = new Path2D.Double();
                shield.moveTo(width * 0.5, height * 0.16);
                shield.lineTo(width * 0.76, height * 0.28);
                shield.lineTo(width * 0.72, height * 0.66);
                shield.lineTo(width * 0.5, height * 0.84);
                shield.lineTo(width * 0.28, height * 0.66);
                shield.lineTo(width * 0.24, height * 0.28);
                shield.closePath();
                g.setColor(new Color(255, 210, 74, 70));
                g.fill(shield);
                g.setColor(new Color(255, 236, 160, 190));
                g.setStroke(new BasicStroke(4f));
                g.draw(shield);
                g.setStroke(new BasicStroke(3f));
                g.drawLine((int) (width * 0.5), (int) (height * 0.26), (int) (width * 0.5), (int) (height * 0.72));
                g.drawLine((int) (width * 0.36), (int) (height * 0.43), (int) (width * 0.64), (int) (height * 0.43));
            }
            case "traces" -> {
                g.setStroke(new BasicStroke(2f));
                for (int i = 0; i < 14; i++) {
                    int x = random.nextInt(width);
                    int y = random.nextInt(height);
                    g.setColor(new Color(160, 255, 220, 90));
                    Path2D path = new Path2D.Double();
                    path.moveTo(x, y);
                    for (int step = 0; step < 3; step++) {
                        x += random.nextBoolean() ? 30 : 0;
                        y += random.nextBoolean() ? 22 : 0;
                        path.lineTo(x, y);
                    }
                    g.draw(path);
                    g.fillOval(x - 2, y - 2, 5, 5);
                }
            }
            case "scanlines" -> {
                for (int y = 0; y < height; y += 4) {
                    g.setColor(new Color(255, 255, 255, 22));
                    g.drawLine(0, y, width, y);
                }
            }
            case "cracks" -> {
                g.setColor(new Color(150, 120, 255, 90));
                for (int i = 0; i < 20; i++) {
                    int x = random.nextInt(width);
                    int y = random.nextInt(height);
                    Path2D path = new Path2D.Double();
                    path.moveTo(x, y);
                    for (int step = 0; step < 4; step++) {
                        x += random.nextInt(26) - 13;
                        y += random.nextInt(26) - 13;
                        path.lineTo(x, y);
                    }
                    g.draw(path);
                }
            }
            default -> { }
        }
        g.dispose();
        return image;
    }

    private static int[] ramp(int... colors) {
        Color[] parsed = new Color[colors.length];
        for (int i = 0; i < colors.length; i++) parsed[i] = new Color(colors[i]);
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            double position = i / 255.0 * (parsed.length - 1);
            int index = Math.min(parsed.length - 2, (int) position);
            float t = (float) (position - index);
            Color a = parsed[index];
            Color b = parsed[Math.min(parsed.length - 1, index + 1)];
            lut[i] = 0xFF000000
                | ((int) (a.getRed() + (b.getRed() - a.getRed()) * t) << 16)
                | ((int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t) << 8)
                | (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        }
        return lut;
    }

    private static int[] spectrum() {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) lut[i] = Color.HSBtoRGB(i / 256f, 0.85f, 1f);
        return lut;
    }

    private static float fbm(float x, float y, int seed, int octaves) {
        float value = 0;
        float amplitude = 0.5f;
        float total = 0;
        for (int i = 0; i < octaves; i++) {
            value += valueNoise(x, y, seed + i * 17) * amplitude;
            total += amplitude;
            x *= 2.02f;
            y *= 2.02f;
            amplitude *= 0.5f;
        }
        return value / total;
    }

    private static float valueNoise(float x, float y, int seed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        float sx = fx * fx * (3 - 2 * fx);
        float sy = fy * fy * (3 - 2 * fy);
        float n00 = hash(x0, y0, seed);
        float n10 = hash(x0 + 1, y0, seed);
        float n01 = hash(x0, y0 + 1, seed);
        float n11 = hash(x0 + 1, y0 + 1, seed);
        float top = n00 + (n10 - n00) * sx;
        float bottom = n01 + (n11 - n01) * sx;
        return top + (bottom - top) * sy;
    }

    private static float hash(int x, int y, int seed) {
        int value = x * 374761393 + y * 668265263 + seed * 69069;
        value = (value ^ (value >> 13)) * 1274126177;
        value = value ^ (value >> 16);
        return (value & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private GenerateWardrobeAssets() {}
}
