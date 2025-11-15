package com.zihaomc.ghost.features.autocraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.utils.LogUtil;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动合成配方的中央管理器。
 * 负责从配置文件加载、注册、存储和查询所有可用的合成配方。
 */
public class AutoCraftRecipeManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String RECIPES_FILE_NAME = "autocraft_recipes.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, AutoCraftRecipe> recipes = new ConcurrentHashMap<>();

    private static class RecipeDefinition {
        String recipeKey;
        String ingredientDisplayName;
        int requiredAmount;
    }

    public static void initialize() {
        File recipesFile = getRecipesFile();
        if (!recipesFile.exists()) {
            createDefaultRecipesFile(recipesFile);
        }
        loadRecipesFromFile(recipesFile);
    }

    public static void reloadRecipes() {
        recipes.clear();
        loadRecipesFromFile(getRecipesFile());
    }

    public static void saveRecipes() {
        File file = getRecipesFile();
        List<RecipeDefinition> definitions = recipes.values().stream()
            .map(recipe -> {
                RecipeDefinition def = new RecipeDefinition();
                def.recipeKey = recipe.recipeKey;
                def.ingredientDisplayName = recipe.ingredientDisplayName;
                def.requiredAmount = recipe.requiredAmount;
                return def;
            })
            .collect(Collectors.toList());
        
        try (Writer writer = new FileWriter(file)) {
            addFileHeader(writer);
            for (int i = 0; i < definitions.size(); i++) {
                writer.write(GSON.toJson(definitions.get(i)));
                if (i < definitions.size() - 1) {
                    writer.write(",\n");
                }
            }
            writer.write("\n]");
        } catch (IOException e) {
            LogUtil.error("Failed to save auto-craft recipes file.");
            e.printStackTrace();
        }
    }

    private static void loadRecipesFromFile(File file) {
        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<List<RecipeDefinition>>(){}.getType();
            List<RecipeDefinition> definitions = GSON.fromJson(reader, type);

            if (definitions != null) {
                for (RecipeDefinition def : definitions) {
                    registerRecipe(new AutoCraftRecipe(
                        def.recipeKey,
                        def.ingredientDisplayName,
                        def.requiredAmount
                    ));
                }
            }
        } catch (Exception e) {
            LogUtil.error(LangUtil.translate("ghost.autocraft.error.recipe_file_read_error"));
            e.printStackTrace();
        }
    }

    private static void createDefaultRecipesFile(File file) {
        List<RecipeDefinition> defaultRecipes = new ArrayList<>();
        
        RecipeDefinition mithril = new RecipeDefinition();
        mithril.recipeKey = "mithril";
        mithril.ingredientDisplayName = "Mithril";
        mithril.requiredAmount = 320;
        defaultRecipes.add(mithril);

        RecipeDefinition handStone = new RecipeDefinition();
        handStone.recipeKey = "handstone";
        handStone.ingredientDisplayName = "Hand Stone";
        handStone.requiredAmount = 576;
        defaultRecipes.add(handStone);

        try (Writer writer = new FileWriter(file)) {
            addFileHeader(writer);
            for (int i = 0; i < defaultRecipes.size(); i++) {
                writer.write(GSON.toJson(defaultRecipes.get(i)));
                if (i < defaultRecipes.size() - 1) {
                    writer.write(",\n");
                }
            }
            writer.write("\n]");
        } catch (IOException e) {
            LogUtil.error("Failed to create default auto-craft recipes file.");
            e.printStackTrace();
        }
    }

    private static void addFileHeader(Writer writer) throws IOException {
        String fileHeader = "[\n  // 这是自动合成的配方文件。\n" +
                            "  // 你可以在这里添加或修改配方，也可以使用 /autocraft add|remove 命令。\n" +
                            "  // recipeKey: 命令中使用的唯一名称, e.g., /autocraft start <recipeKey>\n" +
                            "  // ingredientDisplayName: 材料的游戏内显示名称 (空格请用下划线代替, e.g., Hand_Stone)\n" +
                            "  // requiredAmount: 合成一个成品需要的材料总数\n" +
                            "  // 修改后，请在游戏中输入 /autocraft reload 来重新加载。\n";
        writer.write(fileHeader);
    }
    
    private static File getRecipesFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, RECIPES_FILE_NAME);
    }

    public static void registerRecipe(AutoCraftRecipe recipe) {
        if (recipe != null && recipe.recipeKey != null) {
            recipes.put(recipe.recipeKey.toLowerCase(), recipe);
        }
    }

    public static void removeRecipe(String key) {
        if (key != null) {
            recipes.remove(key.toLowerCase());
        }
    }

    public static AutoCraftRecipe getRecipe(String key) {
        return recipes.get(key.toLowerCase());
    }

    public static Collection<AutoCraftRecipe> getAllRecipes() {
        return Collections.unmodifiableCollection(recipes.values());
    }

    public static Collection<String> getAllRecipeKeys() {
        return Collections.unmodifiableSet(recipes.keySet());
    }
}