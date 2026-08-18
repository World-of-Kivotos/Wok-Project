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

/** P3 导体、保护件与终局发电机配方的综合回归测试。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerEndgameRecipeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_endgame_recipes";

    private PowerEndgameRecipeGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void endgameRecipesKeepExactMaterialCountsAndProgression(GameTestHelper helper) {
        for (ConductorMaterial material : new ConductorMaterial[]{
                ConductorMaterial.GRAPHENE,
                ConductorMaterial.NBTI_SUPERCONDUCTOR,
                ConductorMaterial.YBCO_SUPERCONDUCTOR}) {
            Item wireInput = switch (material) {
                case GRAPHENE -> PowerRegistry.GRAPHENE_SHEET.get();
                case NBTI_SUPERCONDUCTOR -> PowerRegistry.NBTI_CONDUCTOR.get();
                case YBCO_SUPERCONDUCTOR -> PowerRegistry.YBCO_TAPE.get();
                default -> throw new IllegalStateException("unexpected endgame material " + material);
            };
            assertRecipe(helper, PowerRegistry.WIRE_ITEMS.get(material).getId(),
                    Map.of(wireInput, 3), PowerRegistry.WIRE_ITEMS.get(material).get(), 6);
            assertRecipe(helper, PowerRegistry.CABLE_ITEMS.get(material).getId(),
                    Map.of(PowerRegistry.WIRE_ITEMS.get(material).get(), 3,
                            PowerRubberRegistry.INSULATION_SILICONE.get(), 3),
                    PowerRegistry.CABLE_ITEMS.get(material).get(), 6);
        }

        assertRecipe(helper, PowerRegistry.TUNGSTEN_HEAT_RESISTANT_CABLE_ITEM.getId(),
                Map.of(PowerMineralRegistry.ingot(PowerMineral.TUNGSTEN).get(), 3,
                        PowerRubberRegistry.INSULATION_SILICONE.get(), 3),
                PowerRegistry.TUNGSTEN_HEAT_RESISTANT_CABLE_ITEM.get(), 6);
        assertRecipe(helper, PowerRegistry.NICHROME_FUSE.getId(),
                Map.of(PowerMineralRegistry.ingot(PowerMineral.NICKEL).get(), 4,
                        PowerMineralRegistry.ingot(PowerMineral.CHROMIUM).get(), 1),
                PowerRegistry.NICHROME_FUSE.get(), 4);

        assertRecipe(helper, PowerRegistry.LOW_TEMPERATURE_CONTROLLER_ITEM.getId(),
                Map.of(PowerMachineRegistry.PHOSPHORUS_DEOXIDIZED_COPPER_INGOT.get(), 4,
                        PowerRegistry.NBTI_CONDUCTOR.get(), 2,
                        PowerMachineRegistry.OFE_COPPER_INGOT.get(), 2,
                        PowerMachineRegistry.LIQUID_NITROGEN_CANISTER.get(), 1),
                PowerRegistry.LOW_TEMPERATURE_CONTROLLER_ITEM.get(), 1);
        assertRecipe(helper, PowerRegistry.FUTURE_FUEL_CORE.getId(),
                Map.of(PowerRegistry.GRAPHENE_SHEET.get(), 4,
                        PowerRegistry.YBCO_TAPE.get(), 4, Items.NETHER_STAR, 1),
                PowerRegistry.FUTURE_FUEL_CORE.get(), 1);
        assertRecipe(helper, PowerRegistry.FUTURE_ENERGY_GENERATOR_ITEM.getId(),
                Map.of(PowerRegistry.GRAPHENE_SHEET.get(), 4,
                        PowerRegistry.YBCO_TAPE.get(), 4,
                        PowerRegistry.MODERN_GENERATOR_ITEM.get(), 1),
                PowerRegistry.FUTURE_ENERGY_GENERATOR_ITEM.get(), 1);
        helper.succeed();
    }

    private static void assertRecipe(GameTestHelper helper, ResourceLocation recipeId,
                                     Map<Item, Integer> expectedInputs, Item expectedResult,
                                     int expectedResultCount) {
        Optional<? extends Recipe<?>> recipeOptional = helper.getLevel().getRecipeManager().byKey(recipeId);
        helper.assertTrue(recipeOptional.isPresent(), recipeId + " 配方必须存在");
        Recipe<?> recipe = recipeOptional.orElseThrow(
                () -> new IllegalStateException("missing recipe " + recipeId));
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
