package com.miningdim.power.cable;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import com.miningdim.power.rubber.PowerRubberRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** P2 导体与现代发电机的数据包配方回归测试。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerMidgameRecipeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_midgame_recipes";

    private PowerMidgameRecipeGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void platedWiresRequireEightBaseWiresAndOnePlatingIngot(GameTestHelper helper) {
        assertRecipe(helper, PowerRegistry.WIRE_ITEMS.get(ConductorMaterial.TINNED_COPPER).getId(),
                Map.of(PowerRegistry.WIRE_ITEMS.get(ConductorMaterial.COPPER).get(), 8,
                        PowerMineralRegistry.ingot(PowerMineral.TIN).get(), 1),
                PowerRegistry.WIRE_ITEMS.get(ConductorMaterial.TINNED_COPPER).get(), 8);
        assertRecipe(helper, PowerRegistry.WIRE_ITEMS.get(ConductorMaterial.SILVER_PLATED_COPPER).getId(),
                Map.of(PowerRegistry.WIRE_ITEMS.get(ConductorMaterial.OFE_COPPER).get(), 8,
                        PowerMineralRegistry.ingot(PowerMineral.SILVER).get(), 1),
                PowerRegistry.WIRE_ITEMS.get(ConductorMaterial.SILVER_PLATED_COPPER).get(), 8);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void t4ThroughT9CablesRequireThreeMatchingWiresAndInsulation(GameTestHelper helper) {
        assertCableRecipe(helper, ConductorMaterial.TINNED_COPPER, PowerRubberRegistry.INSULATION_PE.get());
        assertCableRecipe(helper, ConductorMaterial.OFC_COPPER, PowerRubberRegistry.INSULATION_EPR.get());
        assertCableRecipe(helper, ConductorMaterial.OFE_COPPER, PowerRubberRegistry.INSULATION_XLPE.get());
        assertCableRecipe(helper, ConductorMaterial.SILVER_PLATED_COPPER, PowerRubberRegistry.INSULATION_XLPE.get());
        assertCableRecipe(helper, ConductorMaterial.GOLD, PowerRubberRegistry.INSULATION_XLPE.get());
        assertCableRecipe(helper, ConductorMaterial.SILVER, PowerRubberRegistry.INSULATION_SILICONE.get());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void modernGeneratorAndFuelCoreKeepTheirExactProgressionCosts(GameTestHelper helper) {
        assertRecipe(helper, PowerRegistry.MODERN_GENERATOR_ITEM.getId(),
                Map.of(PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get(), 1,
                        PowerMachineRegistry.OFE_COPPER_INGOT.get(), 4,
                        PowerMachineRegistry.GOLD_4N_INGOT.get(), 4),
                PowerRegistry.MODERN_GENERATOR_ITEM.get(), 1);
        assertRecipe(helper, PowerRegistry.MODERN_FUEL_CORE.getId(),
                Map.of(PowerMachineRegistry.OFE_COPPER_INGOT.get(), 4, Items.BLAZE_ROD, 5),
                PowerRegistry.MODERN_FUEL_CORE.get(), 1);
        helper.succeed();
    }

    private static void assertCableRecipe(GameTestHelper helper, ConductorMaterial material, Item insulation) {
        assertRecipe(helper, PowerRegistry.CABLE_ITEMS.get(material).getId(),
                Map.of(PowerRegistry.WIRE_ITEMS.get(material).get(), 3, insulation, 3),
                PowerRegistry.CABLE_ITEMS.get(material).get(), 6);
    }

    private static void assertRecipe(GameTestHelper helper, ResourceLocation recipeId, Map<Item, Integer> expectedInputs,
                                     Item expectedResult, int expectedResultCount) {
        Optional<? extends Recipe<?>> recipeOptional = helper.getLevel().getRecipeManager().byKey(recipeId);
        helper.assertTrue(recipeOptional.isPresent(), recipeId + " 配方必须存在");
        Recipe<?> recipe = recipeOptional.orElseThrow(() -> new IllegalStateException("missing recipe " + recipeId));
        List<Ingredient> ingredients = recipe.getIngredients();
        int expectedIngredientCount = expectedInputs.values().stream().mapToInt(Integer::intValue).sum();
        helper.assertTrue(ingredients.size() == expectedIngredientCount,
                recipeId + " 原料槽位数必须为 " + expectedIngredientCount + ", 实际=" + ingredients.size());
        for (Map.Entry<Item, Integer> expected : expectedInputs.entrySet()) {
            int actualCount = countMatchingIngredients(ingredients, expected.getKey());
            helper.assertTrue(actualCount == expected.getValue(), recipeId + " 中 " + expected.getKey()
                    + " 数量必须为 " + expected.getValue() + ", 实际=" + actualCount);
        }
        for (Ingredient ingredient : ingredients) {
            boolean matchesExpectedInput = expectedInputs.keySet().stream()
                    .anyMatch(item -> ingredient.test(new ItemStack(item)));
            helper.assertTrue(matchesExpectedInput, recipeId + " 含有未登记的原料 " + ingredient);
        }
        ItemStack result = recipe.getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(result.is(expectedResult) && result.getCount() == expectedResultCount,
                recipeId + " 结果必须为 " + expectedResult + " x" + expectedResultCount + ", 实际=" + result);
    }

    private static int countMatchingIngredients(List<Ingredient> ingredients, Item expectedItem) {
        return (int) ingredients.stream().filter(ingredient -> ingredient.test(new ItemStack(expectedItem))).count();
    }
}
