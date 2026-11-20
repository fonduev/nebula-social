import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Genera el catalogo de cosmeticos que sirve el backend.
 *
 * Entrada: la carpeta `assets/polyplus` del jar del mod (cosmetics + textures/cosmetics + capes).
 * Salida: `social/assets/cosmetics` con los modelos, una textura HD por paleta y un catalog.json.
 *
 * HD: cada modelo se sube de resolucion hasta >= 256 px de lado (x4 desde 64, x2 desde 256).
 * Para que el mapeado siga cuadrando, las UV de caja se convierten a caras explicitas usando
 * la misma formula que `BedrockMesh.buildBoxQuads` del cliente, y luego todo se multiplica por
 * el factor. Las caras ya explicitas solo se multiplican.
 *
 * Uso:
 *   javac -cp gson.jar -d <tmp> GenerateWardrobeAssets.java
 *   java -cp <tmp>;gson.jar GenerateWardrobeAssets "C:/.../assets/polyplus" "C:/.../assets/cosmetics"
 */
public final class GenerateWardrobeAssets {

    private record Base(String slug, String name, String type, String slot, String source, boolean cape) {}

    /** `targetHue` nulo = dejar el arte original; si no, se gira el tono dominante hasta ahi. */
    private record Palette(String name, String slug, Float targetHue, float saturation, float brightness) {}

    /** Modelos 3D que ya vienen en el jar (slot al que pertenecen). */
    private static final Base[] BASES = {
        new Base("halo_angel", "Angel Halo", "hat", "hat", "textures/cosmetics/halo_angel.png", false),
        new Base("halo_cyber", "Cyber Halo", "hat", "hat", "textures/cosmetics/halo_cyber.png", false),
        new Base("halo_demon", "Demon Halo", "hat", "hat", "textures/cosmetics/halo_demon.png", false),
        new Base("halo_sakura", "Sakura Halo", "hat", "hat", "textures/cosmetics/halo_sakura.png", false),
        new Base("halo_void", "Void Crown", "hat", "hat", "textures/cosmetics/halo_void.png", false),
        new Base("nebula_halo", "Nebula Halo", "hat", "hat", "textures/cosmetics/nebula_halo.png", false),
        new Base("santa_hat", "Santa Hat", "hat", "hat", "textures/cosmetics/santa_hat.png", false),
        new Base("reindeer_antlers", "Reindeer Antlers", "hat", "hat", "textures/cosmetics/reindeer_antlers.png", false),
        new Base("wings_valkyrie", "Valkyrie Wings", "wings", "wings", "textures/cosmetics/wings_valkyrie.png", false),
        new Base("wings_dragon", "Dragon Wings", "wings", "wings", "textures/cosmetics/wings_dragon.png", false),
        new Base("wings_cyber", "Cyber Wings", "wings", "wings", "textures/cosmetics/wings_cyber.png", false),
        new Base("wings_snowflake", "Snowflake Wings", "wings", "wings", "textures/cosmetics/wings_snowflake.png", false),
        new Base("nebula_wings", "Nebula Wings", "wings", "wings", "textures/cosmetics/nebula_wings.png", false),
        new Base("shoulder_elf", "Elf Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_elf.png", false),
        new Base("shoulder_reindeer", "Reindeer Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_reindeer.png", false),
        new Base("shoulder_snowman", "Snowman Companion", "shoulder", "shoulder", "textures/cosmetics/shoulder_snowman.png", false),
        new Base("holiday_scarf", "Holiday Scarf", "shoulder", "shoulder", "textures/cosmetics/holiday_scarf.png", false),
        new Base("pet_giftbox", "Giftbox Pet", "pet", "pet", "textures/cosmetics/pet_giftbox.png", false),
        new Base("pet_reindeer", "Baby Reindeer", "pet", "pet", "textures/cosmetics/pet_reindeer.png", false),
        new Base("cape_amethyst", "Amethyst Cape", "cape", "cape", "capes/nebula_amethyst.png", true),
        new Base("cape_galaxy", "Galaxy Cape", "cape", "cape", "capes/nebula_galaxy.png", true),
        new Base("cape_midnight", "Midnight Cape", "cape", "cape", "capes/nebula_midnight.png", true),
        new Base("cape_nova", "Nova Cape", "cape", "cape", "capes/nebula_nova.png", true),
    };

    /** Paletas: el mismo arte con otra dominante de color. */
    private static final Palette[] PALETTES = {
        new Palette("Prism", "prism", null, 1.00f, 1.00f),
        new Palette("Amethyst", "amethyst", 275f, 1.25f, 1.05f),
        new Palette("Aurora", "aurora", 165f, 1.05f, 1.00f),
        new Palette("Solar", "solar", 45f, 1.15f, 1.10f),
        new Palette("Crimson", "crimson", 350f, 1.20f, 1.00f),
        new Palette("Frost", "frost", 195f, 0.75f, 1.15f),
        new Palette("Emerald", "emerald", 140f, 1.15f, 1.00f),
        new Palette("Rose", "rose", 330f, 1.30f, 1.05f),
        new Palette("Cyber", "cyber", 300f, 1.45f, 1.10f),
        new Palette("Obsidian", "obsidian", 260f, 0.35f, 0.55f),
        new Palette("Celestial", "celestial", 50f, 0.20f, 1.30f),
        new Palette("Toxic", "toxic", 85f, 1.40f, 1.15f),
        new Palette("Twilight", "twilight", 250f, 0.95f, 0.85f),
        new Palette("Inferno", "inferno", 20f, 1.30f, 1.05f),
    };

    /** Lado minimo al que se sube cada textura (las capas doblan igual). */
    private static final int HD_MIN_SIDE = 256;
    private static final int HD_CAPE_MIN_WIDTH = 512;

    private static final String[] FACE_NAMES = {"down", "up", "west", "north", "east", "south"};

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

        // Las texturas viejas (no HD) no deben sobrevivir si cambia el esquema de nombres.
        try (var walk = Files.walk(target)) {
            walk.filter(p -> p.toString().endsWith(".png")).forEach(p -> p.toFile().delete());
        }

        List<String> entries = new ArrayList<>();
        int written = 0;
        int convertedCubes = 0;

        for (Base base : BASES) {
            Path sourceTexture = source.resolve(base.source());
            if (!Files.isRegularFile(sourceTexture)) {
                System.err.println("falta la textura " + sourceTexture);
                continue;
            }
            BufferedImage art = ImageIO.read(sourceTexture.toFile());
            if (art == null) {
                System.err.println("textura ilegible " + sourceTexture);
                continue;
            }

            int factor = scaleFactor(base.cape(), art.getWidth(), art.getHeight());
            BufferedImage hd = upscale(art, factor);

            String modelPath = null;
            String animationPath = null;
            if (!base.cape()) {
                Path geo = source.resolve("cosmetics/" + base.slug() + ".geo.json");
                Path animation = source.resolve("cosmetics/" + base.slug() + ".animation.json");
                if (!Files.isRegularFile(geo)) {
                    System.err.println("falta el modelo " + geo);
                    continue;
                }
                JsonObject geometry = JsonParser.parseString(Files.readString(geo, StandardCharsets.UTF_8)).getAsJsonObject();
                convertedCubes += scaleGeometryUv(geometry, factor);
                // Con factor 1 el modelo se queda como esta: convertir UV de caja a caras solo es
                // necesario para reescalar el atlas, y tocarlo de gratis es riesgo sin premio.
                // Compacto a proposito: el zip se descarga 322 veces en la primera carga del
                // wardrobe, y el pretty-print triplicaba el tamaño de la geometria.
                String output = factor <= 1
                    ? Files.readString(geo, StandardCharsets.UTF_8)
                    : new GsonBuilder().create().toJson(geometry) + "\n";
                Files.writeString(models.resolve(base.slug() + ".geo.json"), output, StandardCharsets.UTF_8);
                modelPath = "models/" + base.slug() + ".geo.json";
                if (Files.isRegularFile(animation)) {
                    Files.copy(animation, models.resolve(base.slug() + ".animation.json"), StandardCopyOption.REPLACE_EXISTING);
                    animationPath = "models/" + base.slug() + ".animation.json";
                }
            }

            for (Palette palette : PALETTES) {
                String slug = base.slug() + "-" + palette.slug();
                String texturePath = "textures/" + slug + ".png";
                BufferedImage tinted = recolor(hd, palette);
                ImageIO.write(tinted, "png", textures.resolve(slug + ".png").toFile());
                written++;

                entries.add(entryJson(slug, base, palette, texturePath, modelPath, animationPath));
            }
        }

        Path catalog = target.resolve("catalog.json");
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"items\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            json.append("    ").append(entries.get(i));
            json.append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(catalog, json.toString(), StandardCharsets.UTF_8);

        System.out.println("texturas HD: " + written);
        System.out.println("cosmeticos: " + entries.size());
        System.out.println("cubos convertidos a caras explicitas: " + convertedCubes);
        System.out.println("catalogo: " + catalog.toAbsolutePath());
    }

    /** Factor HD: sube hasta 256 px de lado en modelos y 512 de ancho en capas. */
    private static int scaleFactor(boolean cape, int width, int height) {
        int side = Math.min(width, height);
        int targetSide = cape ? HD_CAPE_MIN_WIDTH / 2 : HD_MIN_SIDE;
        int factor = Math.max(1, Math.round((float) targetSide / side));
        return Math.min(factor, 8);
    }

    private static String entryJson(
        String slug, Base base, Palette palette, String texture, String model, String animation) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        out.append("\"slug\":\"").append(slug).append("\",");
        out.append("\"base\":\"").append(base.slug()).append("\",");
        out.append("\"name\":\"").append(escape(base.name() + " (" + palette.name() + ")")).append("\",");
        out.append("\"type\":\"").append(base.type()).append("\",");
        out.append("\"slots\":[\"").append(base.slot()).append("\"],");
        out.append("\"model\":").append(model == null ? "null" : "\"" + model + "\"").append(',');
        out.append("\"animation\":").append(animation == null ? "null" : "\"" + animation + "\"").append(',');
        out.append("\"texture\":\"").append(texture).append("\"");
        out.append('}');
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------------------------
    // Geometria: UV de caja -> caras explicitas, y todo x factor
    // ------------------------------------------------------------------------------------

    /**
     * Reescala la geometria al atlas HD. Devuelve cuantos cubos con UV de caja se convirtieron.
     *
     * La formula es la de `BedrockMesh.buildBoxQuads`: cada cubo ocupa
     * (2*profundidad + 2*ancho) x (profundidad + alto) px a partir de su origen UV, y de ahi
     * salen las seis caras. La cara de abajo se muestrea al reves (v1 -> v0), cosa que se
     * conserva con un `uv_size` vertical negativo.
     */
    private static int scaleGeometryUv(JsonObject root, int factor) {
        JsonArray geometries = root.has("minecraft:geometry")
            ? root.getAsJsonArray("minecraft:geometry")
            : root.has("geometry") && root.get("geometry").isJsonArray()
                ? root.getAsJsonArray("geometry")
                : null;
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
                    if (expandBoxUv(cube, uv.getAsJsonArray(), factor)) converted++;
                } else if (uv.isJsonObject()) {
                    scaleFaceUv(uv.getAsJsonObject(), factor);
                }
            }
        }
        return converted;
    }

    private static boolean expandBoxUv(JsonObject cube, JsonArray boxUv, int factor) {
        double[] size = vec3(cube, "size", new double[] {1, 1, 1});
        double width = Math.abs(size[0]);
        double height = Math.abs(size[1]);
        double depth = Math.abs(size[2]);

        // Blockbench redondea a la baja las dimensiones del atlas; el cliente hace lo mismo.
        double uvWidth = Math.floor(width);
        double uvHeight = Math.floor(height);
        double uvDepth = Math.floor(depth);

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
        // down se muestrea de abajo a arriba: (u1,v1) -> (u2,v0)
        faces.add("down", face(u1, v1, uvWidth, v0 - v1, factor));
        faces.add("up", face(u2, v0, uvWidth, v1 - v0, factor));
        faces.add("west", face(u0, v1, uvDepth, v2 - v1, factor));
        faces.add("north", face(u1, v1, uvWidth, v2 - v1, factor));
        faces.add("east", face(u2, v1, uvDepth, v2 - v1, factor));
        faces.add("south", face(u3, v1, uvWidth, v2 - v1, factor));

        cube.add("uv", faces);
        return true;
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
        for (String name : FACE_NAMES) {
            if (!faces.has(name)) continue;
            JsonObject face = faces.getAsJsonObject(name);
            if (face.has("uv") && face.get("uv").isJsonArray()) {
                JsonArray uv = face.getAsJsonArray("uv");
                for (int i = 0; i < uv.size(); i++) {
                    uv.set(i, new com.google.gson.JsonPrimitive(uv.get(i).getAsDouble() * factor));
                }
            }
            if (face.has("uv_size") && face.get("uv_size").isJsonArray()) {
                JsonArray uvSize = face.getAsJsonArray("uv_size");
                for (int i = 0; i < uvSize.size(); i++) {
                    uvSize.set(i, new com.google.gson.JsonPrimitive(uvSize.get(i).getAsDouble() * factor));
                }
            }
        }
    }

    private static double[] vec3(JsonObject object, String key, double[] fallback) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return fallback;
        JsonArray array = object.getAsJsonArray(key);
        double[] out = fallback.clone();
        for (int i = 0; i < Math.min(array.size(), 3); i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }

    // ------------------------------------------------------------------------------------
    // Imagen: escalado HD y paletas
    // ------------------------------------------------------------------------------------

    private static BufferedImage upscale(BufferedImage source, int factor) {
        if (factor <= 1) return source;
        int width = source.getWidth() * factor;
        int height = source.getHeight() * factor;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        AffineTransformOp op = new AffineTransformOp(
            AffineTransform.getScaleInstance(factor, factor),
            new RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC));
        op.filter(source, out);
        return out;
    }

    /** Rota el tono y ajusta saturacion/brillo sin tocar el alfa ni los pixeles transparentes. */
    private static BufferedImage recolor(BufferedImage source, Palette palette) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        float shift = palette.targetHue() == null ? 0f : palette.targetHue() - dominantHue(source);
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
                float hue = ((hsb[0] * 360f + shift) % 360f + 360f) % 360f;
                float saturation = clamp(hsb[1] * palette.saturation());
                float brightness = clamp(hsb[2] * palette.brightness());
                int rgb = Color.HSBtoRGB(hue / 360f, saturation, brightness);
                out.setRGB(x, y, (alpha << 24) | (rgb & 0x00FFFFFF));
            }
        }
        return out;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * Tono medio (circular) del arte, ponderado por saturacion: es el color que domina la
     * textura, asi "Crimson" sale rojo en todos los modelos y no solo en los que ya eran rojos.
     */
    private static float dominantHue(BufferedImage image) {
        double cos = 0, sin = 0;
        float[] hsb = new float[3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) == 0) continue;
                Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
                if (hsb[1] < 0.12f) continue;
                double angle = Math.toRadians(hsb[0] * 360.0);
                double weight = hsb[1] * Math.max(hsb[2], 0.2f);
                cos += Math.cos(angle) * weight;
                sin += Math.sin(angle) * weight;
            }
        }
        if (cos == 0 && sin == 0) return 0f;
        double degrees = Math.toDegrees(Math.atan2(sin, cos));
        return (float) ((degrees + 360.0) % 360.0);
    }

    private GenerateWardrobeAssets() {}
}
