package com.miningdim.caseopening;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.caseopening.store.SkinAssetRow;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Three case.* actions. Identity and balances always come from the authenticated ServerPlayer sender. */
public final class CaseWebUiActions {

    private static final Gson GSON = new Gson();
    static final int OWNED_RESPONSE_LIMIT = 60;

    record OwnedSlice(List<SkinAssetRow> assets, int total) {
    }

    private CaseWebUiActions() {
    }

    public static void registerAll() {
        WebUiServerDispatcher.register("case.state", STATE);
        WebUiServerDispatcher.register("case.open", OPEN);
        WebUiServerDispatcher.register("case.apply", APPLY);
    }

    static final WebUiAction STATE = (sender, payload) -> {
        CaseOpeningService service = CaseServices.service();
        List<SkinAssetRow> owned = service.ownedAssets(sender);
        OwnedSlice ownedSlice = ownedSlice(owned);
        Map<String, Integer> ownedCounts = new HashMap<>();
        for (SkinAssetRow asset : owned) {
            ownedCounts.merge(asset.skinId(), 1, Integer::sum);
        }

        JsonObject response = new JsonObject();
        response.addProperty("enabled", service.enabled());
        response.addProperty("caseId", CaseCatalog.CASE_ID);
        response.addProperty("displayName", CaseCatalog.DISPLAY_NAME);
        response.addProperty("creditCost", service.creditCost());
        response.addProperty("azureCost", service.azureCost());
        response.add("wallet", wallet(service.wallet(sender)));

        CaseWeights weights = service.weights();
        JsonArray weightArray = new JsonArray();
        for (CaseRarity rarity : CaseRarity.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("rarity", rarity.id());
            entry.addProperty("weight", weights.weight(rarity));
            weightArray.add(entry);
        }
        response.add("weights", weightArray);

        JsonArray skins = new JsonArray();
        for (CaseSkin skin : CaseCatalog.skins()) {
            JsonObject entry = skin(skin);
            entry.addProperty("ownedCount", ownedCounts.getOrDefault(skin.skinId(), 0));
            skins.add(entry);
        }
        response.add("skins", skins);

        JsonArray assets = new JsonArray();
        for (SkinAssetRow asset : ownedSlice.assets()) {
            assets.add(asset(asset));
        }
        response.add("owned", assets);
        response.addProperty("ownedTotal", ownedSlice.total());
        return GSON.toJson(response);
    };

    static final WebUiAction OPEN = (sender, payload) -> {
        UUID openingId = requiredUuid(payload, "openingId");
        String caseId = requestedCaseId(payload);
        CaseOpeningService service = CaseServices.service();
        CaseOpeningService.OpenResult result = service.open(sender, openingId, caseId);

        JsonObject response = new JsonObject();
        response.addProperty("openingId", result.opening().openingId().toString());
        response.addProperty("replayed", result.replayed());
        response.addProperty("stopIndex", result.opening().stopIndex());
        response.add("wallet", wallet(service.wallet(sender)));
        response.add("result", asset(result.asset()));
        JsonArray reel = new JsonArray();
        for (CaseSkin skin : result.reel()) {
            reel.add(skin(skin));
        }
        response.add("reel", reel);
        return GSON.toJson(response);
    };

    static final WebUiAction APPLY = (sender, payload) -> {
        UUID assetId = requiredUuid(payload, "assetId");
        CaseOpeningService.ApplyResult result = CaseServices.service().apply(sender, assetId);
        SkinAssetRow asset = result.asset();
        JsonObject response = new JsonObject();
        response.addProperty("applied", true);
        response.addProperty("assetId", asset.assetId().toString());
        response.addProperty("skinId", asset.skinId());
        response.addProperty("gunId", asset.gunId());
        response.addProperty("displayId", asset.displayId());
        return GSON.toJson(response);
    };

    private static JsonObject wallet(CaseOpeningService.Wallet wallet) {
        JsonObject json = new JsonObject();
        json.addProperty("credit", wallet.credit());
        json.addProperty("azure", wallet.azure());
        return json;
    }

    private static JsonObject skin(CaseSkin skin) {
        JsonObject json = new JsonObject();
        json.addProperty("skinId", skin.skinId());
        json.addProperty("displayName", skin.displayName());
        json.addProperty("rarity", skin.rarity().id());
        json.addProperty("gunId", skin.gunId().toString());
        json.addProperty("displayId", skin.displayId().toString());
        return json;
    }

    private static JsonObject asset(SkinAssetRow asset) {
        CaseSkin catalog = CaseCatalog.requireSkin(asset.skinId());
        JsonObject json = skin(catalog);
        json.addProperty("assetId", asset.assetId().toString());
        json.addProperty("acquiredAt", asset.acquiredAt());
        json.addProperty("tradeLockedUntil", asset.tradeLockedUntil());
        return json;
    }

    static OwnedSlice ownedSlice(List<SkinAssetRow> owned) {
        int total = owned.size();
        return new OwnedSlice(owned.stream().limit(OWNED_RESPONSE_LIMIT).toList(), total);
    }

    private static UUID requiredUuid(JsonObject payload, String field) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            throw invalidRequest("缺少必填字段 " + field);
        }
        try {
            return UUID.fromString(payload.get(field).getAsString());
        } catch (RuntimeException invalid) {
            throw invalidRequest("字段 " + field + " 不是有效 UUID");
        }
    }

    private static String requestedCaseId(JsonObject payload) {
        if (!payload.has("caseId")) {
            return CaseCatalog.CASE_ID;
        }
        if (payload.get("caseId").isJsonNull()) {
            throw invalidRequest("caseId 不能为空");
        }
        final String caseId;
        try {
            caseId = payload.get("caseId").getAsString();
            CaseCatalog.requireCase(caseId);
        } catch (RuntimeException invalid) {
            throw invalidRequest("caseId 无效");
        }
        return caseId;
    }

    private static WebUiBusinessException invalidRequest(String message) {
        return new WebUiBusinessException(WebUiErrorCodes.INVALID_REQUEST, message, false);
    }
}
