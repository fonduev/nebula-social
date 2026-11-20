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

    enum Source { MODEL, CAPE, GENERATED }

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
        model("shoulder_elf", "Elf Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_elf.png"),
        model("shoulder_reindeer", "Reindeer Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_reindeer.png"),
        model("shoulder_snowman", "Snowman Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_snowman.png"),
        model("holiday_scarf", "Holiday Scarf", "shoulder", "shoulder", "textures/cosmetics/holiday_scarf.png"),
        model("pet_giftbox", "Giftbox Pet", "pet", "pet", "textures/cosmetics/pet_giftbox.png"),
        model("pet_reindeer", "Baby Reindeer", "pet", "pet", "textures/cosmetics/pet_reindeer.png"),

        // Aros nuevos: geometria propia y dos acabados por modelo.
        generated("ring_prism", "Prism Ring", "ring_thin", "rainbow", "prismglass"),
        generated("ring_twin", "Twin Ring", "ring_double", "amethyst", "deepsea"),
        generated("crown_spikes", "Spike Crown", "crown_spikes", "gold", "obsidian"),
        generated("orbit_halo", "Orbit Halo", "orbit_orbs", "nebula", "radiance"),

        // Auras: el wardrobe no tenia ninguna.
        generated("aura_ring", "Aura Ring", "aura_ring", "huewheel", "emerald"),
        generated("aura_flames", "Flame Aura", "aura_flames", "lava", "toxic"),
        generated("aura_runes", "Rune Circle", "aura_runes", "arcane", "monochrome"),
        generated("aura_shards", "Crystal Aura", "aura_shards", "sakura", "petalstorm"),

        // Capas: un diseno distinto por capa.
        cape("cape_galaxy", "Galaxy Cape", "galaxy", false),
        cape("cape_aurora", "Aurora Cape", "aurora", false),
        cape("cape_ember", "Ember Cape", "ember", false),
        cape("cape_frost", "Frost Cape", "frost", false),
        cape("cape_cyber", "Cyber Grid Cape", "cyber", false),
        cape("cape_voidrune", "Void Rune Cape", "void", false),
        cape("cape_starfall", "Starfall Cape", "starfall", false),
        cape("cape_solar", "Solar Flare Cape", "solar", false),
        cape("cape_hex", "Hex Mesh Cape", "hex", false),
        cape("cape_cloud", "Cloud Nebula Cape", "cloud", false),
        cape("cape_crest", "Crimson Crest Cape", "crest", false),
        cape("cape_circuit", "Circuit Cape", "circuit", false),
        cape("cape_aurora_drift", "Aurora Drift Cape", "aurora_drift", true),
        cape("cape_ember_flow", "Ember Flow Cape", "ember_flow", true),
        cape("cape_star_twinkle", "Starlight Twinkle Cape", "star_twinkle", true),
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

                    JsonObject geometry = JsonParser.parseString(Files.readString(geo, StandardCharsets.UTF_8)).getAsJsonObject();
                    converted += scaleGeometryUv(geometry, factor);
                    Files.writeString(models.resolve(base.slug() + ".geo.json"),
                        factor <= 1 ? Files.readString(geo, StandardCharsets.UTF_8)
                                     : new GsonBuilder().create().toJson(geometry) + "\n",
                        StandardCharsets.UTF_8);
                    Path animation = source.resolve("cosmetics/" + base.slug() + ".animation.json");
                    String animationPath = null;
                    if (Files.isRegularFile(animation)) {
                        Files.copy(animation, models.resolve(base.slug() + ".animation.json"));
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

    private record Theme(int[] ramp, String gradient, float noise, String deco, float density, int seed) {}

    private static Theme theme(String name) {
        return switch (name) {
            case "rainbow" -> new Theme(spectrum(), "horizontal", 0.05f, "sparkle", 0.4f, 11);
            case "solar" -> new Theme(ramp(0x2b0d00, 0xff6a00, 0xffd24a, 0xfff4c2), "radial", 0.15f, "rays", 0.6f, 12);
            case "aurora" -> new Theme(ramp(0x030b18, 0x0b6b6b, 0x36d6a0, 0x9d7bff, 0x03121a), "vertical", 0.35f, "ribbons", 0.7f, 13);
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
            case "aurora_drift" -> new Theme(ramp(0x02060f, 0x0a4a5a, 0x2ad0b0, 0x8a5cff, 0x04121a), "vertical", 0.42f, "ribbons", 0.9f, 35);
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
