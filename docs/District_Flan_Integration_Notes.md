# 自管区 × Flan 对接说明

> **这是什么**：对照 `flan-1.20.1-1.11.16-forge.jar`（SHA1 `be83187dd6717029930e8ca7c1109f4bcd5cf870`）用 `javap` 与同版本源码核对的对接说明，写 wok-district 后端时照此实现。
>
> **日期**：2026-09-29。
>
> **范围**：只管后端怎么调 Flan。平板界面的假定契约（D 组 D1–D26）在 `webui/src/mock/planned.ts`，两边口径冲突时以本文核对过的 Flan 行为为准、回头改契约。换 Flan 版本前必须按第 2.5 节重新核对。
>
> 正文里提到的 Task A / B 与 source cross-check 是当时的几轮独立核对，中间导出文件没有入库。第一部分是写给服主的结论，第二部分是技术细节（英文原样保留）。

---

**第一部分：给服主的结论**

1. 能做。这个 Flan 版本支持商定的方案：OP 圈自管区，区务长在区里划地块，户主给朋友、其他住户、外人分别开关权限。这些操作全部由服务器代办，区务长和户主都不用拿任何 Flan 权限。
2. 注意①：Flan 建地块时会带上"区"上的权限组，地块与区的这部分设置并不各自独立。我们的做法是区本身不放任何组和成员，每块地只用自己专有的组。另外 Flan 给每个新领地自带 Co-Owner 和 Visitor 两个组，其中 Co-Owner 能改领地，建议在服务器的 Flan 配置里关掉。
3. 注意②：OP（等级 2 及以上）在所有自管区地块里都能拆方块、开箱子，这是 Flan 写死的。要限制就由我们的模块自己拦；不拦的话，就在守则里写明"管理员可进入"。
   - 服主 2026-09-29 拍板：OP 可以进入所有地块并拥有全部权限，不拦截。
   - 机械动力禁令（自管区内和外围 8 格）是我们服务器自己的规则，不是 Flan 权限；服主 2026-09-29 拍板对 OP 例外，拦截时跳过 OP（`hasPermissions(2)` 且不是 `FakePlayer`：机械手的假玩家带着主人的 UUID，OP 放的机械手不算 OP）。禁的是机器（带方块实体或会转的方块），装饰方块照常可放；OP 的例外只到亲手放置为止，机器同样改不了区内方块（设计文档 22.4–22.6）。
4. 注意③：同一个区里相邻两块地之间，方块自己的动作（活塞、流体、发射器与投掷器、下落的方块、海绵与岩浆点火）由我们的模块在地块边界上拦（阶段 3 起，设计文档 22.9）。爆炸、火、刷怪是按地块分别算的；火焰蔓延、爆炸仍按 Flan 的全区开关。
5. 注意④：一个玩家在一块地里只能属于一个组。既是朋友又是住户的人，我们按"朋友"算。
6. 注意⑤：PvP、爆炸、火烧、刷怪这类开关，只能对整块地统一开或关，不能只给朋友开。自动放方块、挖方块的机器也一样，只能整块地允许或禁止，具体效果要实机确认。
7. 注意⑥：Flan 有改动时不会通知我们。如果有人用 /flan 命令手动改了，我们靠定时检查把它改回来。
8. 注意⑦：领地数据要等世界存档时才写进硬盘。备份文件绝不能放进 data/claims 文件夹（包括它的子文件夹）。领地文件一旦写坏，世界会打不开，所以我们每次写之前先自动备份到别处。
9. 注意⑧：自管区本身也能改大小，但要走程序里的特殊通道。改完后地块是否还在区内，要我们自己检查。
10. 小事：如果区里登记了很多从没进过服的玩家，管理员在区里用 /flan info 可能会卡服。请改用我们自己的界面查看。

---

**Section 2 — Technical: FlanGateway map for Flan 1.20.1-1.11.16 (Forge)**

**Sources.** Most of this comes from Tasks A and B plus the source cross-check. I re-checked the following myself in `javap` dumps of the jar's `claim.Claim` and `claim.ClaimStorage` classes (local working exports, not committed):
- every method signature below;
- `copySizes`, which only copies `minX/maxX/minZ/maxZ/minY/maxY`, sets `removed=false` and calls `setDirty(true)`;
- the Y handling in `createAdminClaim`.

**Environment.**
- Flan's own class, method and field names are not obfuscated, so we can compile against them with `compileOnly`. Minecraft members inside the jar use SRG names. A mixin into Flan classes needs `remap=false`.
- Every call must run on the server thread (`server.execute`). Flan uses plain HashMap and ArrayList with no locks.

### 2.1 Method map

| Gateway op | Flan call | Style | Caveats (verified) |
|---|---|---|---|
| storage(level) | `ClaimStorage.get(ServerLevel)` (static) | direct | One instance per `ServerLevel`. Built at the end of the `ServerLevel` constructor, which also reads the file then. |
| createDistrict | `storage.createAdminClaim(BlockPos a, BlockPos b, ServerLevel, false)` with both Y = `level.getMinBuildHeight()` | direct | Returns `Claim`. Returns `null` if it overlaps another top-level claim. No minimum size, no player, no claim-block cost, no chat message. For 2D claims the lower Y is dropped by `defaultClaimDepth`; A, B and the cross-check say it is then clamped to the world bottom. Top = max build height + 10. Adds `ConfigHandler.CONFIG.defaultGroups`: prevent this with config `defaultGroups: {}`, or swap the public, non-final field to an empty map around the call. Afterwards assert `groups().isEmpty()`. Don't use `%` in the name. |
| findDistrict | `storage.getClaimAt(pos)`, `getFromUUID(uuid)`, `getClaims().get(null)` (null-check it) | direct | These only find top-level claims. Never use `getAdminClaims()`: it NPEs when the dimension has no admin claim. |
| resizeDistrict | (a) `storage.resizeClaim(Claim, BlockPos fromCorner, BlockPos to, ServerPlayer)`<br>(b) `storage.deleteClaim(c, false, ClaimMode.DEFAULT, lvl)` → `c.copySizes(new Claim(p1,p2,null,lvl))` → private `addClaim(Claim)` | (a) direct with a real player<br>(b) reflection | (a) A null player NPEs. It checks `minClaimsize` (100), `minHeight3d`, and overlap with top-level claims and other claim mods. It sends chat to the actor.<br>(b) `copySizes` only rewrites fields, so the claim has to be removed and re-added to fix the chunk index. Our own overlap check: private `Set<DisplayBox> conflicts(Claim,Claim)`, or our own AABB test.<br>Neither route checks that plots stay inside the district. **(b) still needs a live test.** |
| deleteDistrict | `storage.deleteClaim(Claim, boolean, ClaimMode.DEFAULT, ServerLevel)` | direct | The boolean (`updateClaim`) means "also call `c.remove()` on the claim" (bytecode re-checked 2026-09-29). The `ServerLevel` parameter is unused. The module never deletes a district claim (unbind only); GameTests use `true` to clean up. |
| createPlot | `district.tryCreateSubClaim(BlockPos, BlockPos, false)` | direct | **An empty `Set` means success.** Otherwise it returns the conflicting siblings. Called on a plot, it returns `Set.of(parent)`. It does not return the new plot: take `getSubClaim(pos)` or the last element of `getAllSubclaims()`. No containment or minimum-size check, so we validate both. Boxes are closed intervals, so neighbours must not share a coordinate. Plot IDs are unique only among siblings and are not in `claimUUIDMap`; key them as (districtId, plotId). See 2.2 for what gets copied. |
| findPlot | `district.getSubClaim(pos)`, `getAllSubclaims()` + `getClaimID()`, `parentClaim()`, `isSubclaim()` | direct | `getFromUUID` cannot find plots. |
| geometry | `getDimensions()` (ClaimBox, closed), `insideClaim(pos)`, `intersects(Claim)`, `isAdminClaim()` (true when `owner == null`), `getClaimsAt(chunkX, chunkZ)` | direct | Plots are admin claims too, because their owner is null. |
| deletePlot | `district.deleteSubClaim(Claim)` | direct | Calls `sub.remove()`, removes it from the list, and marks the parent dirty. |
| resizePlot (vacant) | Our checks, then `plot.copySizes(new Claim(p1,p2,null,lvl))`. Or `district.resizeSubclaim(sub, fromCorner, to)`, which moves one corner and returns an empty set on success. | direct | `resizeSubclaim` only checks sibling overlap. The throwaway `Claim` constructor applies default groups and marks itself dirty; it has no parent, so this is harmless. |
| createGroup | `plot.editPerms(null, name, anyPerm, -1, true)` | direct | Creates a fresh `HashMap` if the group is absent. Empty groups are saved. |
| setGroupPerm | `plot.editPerms(null, group, perm, mode, true)` | direct | mode 1 = true, 0 = false, -1 or >1 = remove, < -1 = false. Returns `false` for global perms. With `force=true` a null player is fine; the 4-arg overload with null NPEs. Admin claims skip the globally-defined lock. **Never call it on a group the plot inherited when it was created** (shared map). |
| setDefaultPerm | `claim.editGlobalPerms(null, perm, mode)` | direct | Works for global and non-global perms. Same mode rules as above. |
| deleteGroup / clear defaults | (a) Stop them appearing: config `defaultGroups: {}`.<br>(b) Reflection on private final `permissions` (`Map<String, Map<RL,Boolean>>`): `.remove(name)`, then drop the matching `playersGroups` entries, then `setDirty(true)`.<br>(c) `removePermGroup(ServerPlayer, String)` | (a) config (recommended)<br>(b) reflection<br>(c) real player | (c) NPEs with null. It checks `EDITPERMS` first, which fires `PermissionCheckEvent`; a FakePlayer caller is seen as `flan:fake_player`. It also removes that claim's members of the group (jar bytecode 26-65). On a plot it only removes the plot's own key, which is safe. |
| setMember / removeMember | `plot.setPlayerGroup(uuid, group, true)` / `setPlayerGroup(uuid, null, true)` | direct | Never refuses in admin claims. Does not check that the group or UUID exists. **One group per player per claim.** Runtime compares `player.getUUID()`, so offline-mode UUIDs work. |
| readMembers | Reflection on private `playersGroups` (`Map<UUID,String>`), or `claim.toJson(new JsonObject())` and read `PlayerPerms` | reflection / direct | `playersFromGroup` returns names only and silently drops uncached UUIDs. |
| readPerms | `groups()`, `groupHasPerm(g, p)`, `permEnabled(p)` (-1 / 0 / 1), `toJson` → `PermGroup` and `GlobalPerms` | direct | Re-checked in bytecode (2026-09-29): `groupHasPerm` returns -1 when the group or the key is missing, else 1 / 0; `permEnabled` the same for the claim's own `globalPerm`. |
| listPerms | `PermissionManager.INSTANCE.getIds()`, `getAll()` (sorted), `get(RL)`, `isGlobalPermission(RL)` | direct | Filled only after datapacks load. **Correction (2026-09-29):** `INSTANCE` itself is never replaced; its `apply()` swaps the private `permissions` map and `sorted` list, and `getAll()` returns `sorted` directly, so an identity change of `getAll()` means a reload happened (the module caches by that identity, design 20.3). An unknown id gives `get` = null and `isGlobal` = false. The jar ships 67 permission JSONs; `create_contraption` needs Create, so there are 66 in dev. |
| name | `setClaimName(String)` | direct | `getClaimName` uses `String.format`, so no `%`. A plot's default name is "". |
| flush | `storage.save(MinecraftServer, level.dimension())` | direct | Synchronous and non-atomic. Server thread only. |
| enforce / override | Forge `io.github.flemmli97.flan.api.forge.PermissionCheckEvent`, answered with `setResult(InteractionResult)` | event | PASS continues, FAIL denies, anything else allows. Not cancelable. Fires before the OP bypass. Fires once per check, including every exploding block and spawns with a null player. Fires twice for a position inside a plot (parent, then plot). Not fired in the wilderness or for whitelisted fake players. The event carries no claim object, so resolve by position. |
| (never use) | `modifyFakePlayerUUID(uuid, add)` on a district | — | The parent's whitelist returns true before plot delegation, which opens every plot. It is also not marked dirty. |

### 2.2 Invariants the module must keep

- **The parent has no groups and no members when a plot is created.** `tryCreateSubClaim` does four things:
  - `sub.permissions.putAll(parent.permissions)`: the inner maps are shared, and edits are saved.
  - `playersGroups.putAll`: a one-time snapshot of the parent's members.
  - `potions.putAll`.
  - The constructor's default groups are thrown away.
- If the parent ever needs groups (for example for wardens on the street), scrub right after creating a plot: remove the plot's inherited outer keys by reflection, and remove the copied members with `setPlayerGroup(uuid, null, true)`.
- Use a unique group name per plot, for example `p<plotId>_friend`, `p<plotId>_resident`.
- **Never set `edit_perms`, `edit_claim` or `edit_potions` to true** in any plot group, any plot default, or any parent group.
- A new plot's `globalPerm` is pre-filled with true for every permission whose `defaultVal` is true:
  - non-global: `can_stay, drop, enchantment, enderchest, flight, pickup, portal`
  - global: `enderman, lock_items, snow_golem`
  - plus any modifiable entries from the config's `globalDefaultPerms`.

  Reconcile must write the full desired state: explicit 1/0 for values we manage, -1 for keys that should follow the district.
- Global permissions cannot be set per group, only on the whole plot or district with `editGlobalPerms`:
  `animal_spawn, enderman, explosions, fake_player, fire_spread, hurt_player, lightning, lock_items, mob_spawn, piston_border, player_mob_spawn, sculk, snow_golem, water_border, wither`
- Config ALLTRUE/ALLFALSE locks (for example `mob_spawn`, `teleport`) are ignored in admin claims and their plots, so set the district's `globalPerm` explicitly.

### 2.3 Permission resolution: `Claim.canInteract(p, perm, pos, msg)`

1. **Fake players** (`p.getClass() != ServerPlayer.class`):
   - If the UUID is in the parent's `fakePlayers`, it returns true.
   - Otherwise, if it is not in the parent's `playersGroups` (and isn't the owner), perm becomes `flan:fake_player`. A plot member's machine therefore falls under the plot's global `fake_player` value.
2. **Event:** `PermissionCheckEvent`. Any result other than PASS decides.
3. **Config locks:** non-admin claims only, so skipped for us.
4. **Global permission:** if the position is inside a plot, `sub.canInteract`; otherwise `hasPerm`. No group lookup, no OP or owner bypass.
5. **Bypass:** `playerBypassesPermission`:
   - null player → true;
   - `requireExplicitSet` (`may_flight`, `no_hunger`) → false;
   - otherwise `owner` or `isAdminIgnore`, which is:
     - `/flan bypass` toggle → FTB Ranks node `flan.bypass.admin.mode`, or OP ≥ `config.permissionLevel`;
     - else `isAdminClaim() && hasPermissions(2)`, with 2 hard-coded.
6. **Plot delegation:** for any perm except `EDITCLAIM`/`EDITPERMS`, hand off to the plot's `canInteract`, which repeats steps 2–8 inside the plot.
7. **Group value:** if the player is a member and their group map contains the key, return that value. An explicit false stops here.
8. **Defaults:** `hasPerm`:
   - root claim: `globalPerm == true`;
   - plot: its own key if present, otherwise the parent's `== true`.

**Two traps confirmed on 2026-09-29:**
- `mob_spawn` and `animal_spawn` mean "**prevent** natural spawns" when true (`WorldEventsForge.preventMobSpawn` handles only `MobSpawnType.NATURAL` and cancels when the check is true). Every other global permission is true = allow.
- `GameTestHelper.makeMockServerPlayerInLevel()` returns an anonymous subclass (`GameTestHelper$3`), so step 1 treats it as a fake player and rewrites the permission to `flan:fake_player`. Resolution tests must use `new ServerPlayer(server, level, new GameProfile(uuid, name))`.

What this means for us:
- Parent groups are never read inside a plot, except for the edit perms.
- Block-driven movement between plots of one district (pistons, fluids, dispensers and the like) is handled by the module's own boundary guards (design 22.9).
- Explosions, fire, spawns and lightning are checked per plot.

**Parent geometry and allow lists (2026-09-30 review, bytecode re-read on the approved jar).** Flan resolves protection through the top-level claim at a position (`getForPermissionCheck(pos)` → `getClaimAt`), so the parent's Y range decides where plots are protected, and the parent's six public final `AllowedRegistryList` fields (`allowedItems`, `allowedUseBlocks`, `allowedPlaceBlocks`, `allowedBreakBlocks`, `allowedEntityAttack`, `allowedEntityUse`) are consulted before groups and defaults. Relevant API facts:
- `createAdminClaim` / `createClaim` lower the lower corner by `defaultClaimDepth` (default 10) for 2D claims. The public `Claim.extendDownwards(BlockPos)` only lowers a 2D claim's `minY` (`Math.min`), calls `setDirty(true)` and `WebmapCalls.onExtendDownwards`, and returns early for 3D claims (`maxY != null`). `getDimensions()` reports `minY` as the world bottom minus 10 when `defaultClaimDepth == -1`, and the parent's `minY` for plots when `subClaimsInheritParentDepth` is on.
- `removeAllowedItem(int)` is public, removes one entry and its name mapping and marks dirty; remove from the end so the remaining mapping indices stay valid. `read(new JsonArray())` clears the list but not the mapping, after which `addAllowedItem` silently refuses the same names.

The module therefore keeps every district claim 2D and full height (bind refuses 3D claims and extends shallow 2D ones; the reconciler extends and reports) and clears all six lists on bind and reconcile (design 20.3).

### 2.4 Persistence

- **When:** at the RETURN of `ServerLevel.saveLevelData` (ServerWorldMixin). That covers autosave, `/save-all` and shutdown. Nothing is written while `/save-off` is on (vanilla behaviour).
- **Where:** `<dim storage>/data/claims/!AdminClaims.json`, which holds every admin claim in the dimension, with plots nested under `SubClaims`.
- **What gets written:** `save()` writes a file when its root claim is dirty or its owner is in `storage.dirty`. The file is rewritten whole, non-atomically. Every Flan mutator marks dirty, and a plot's dirty flag goes to its parent. Reflection edits and `modifyFakePlayerUUID` do not mark dirty; call `setDirty(true)` yourself.
- **Format:**
  - Root `GlobalPerms` is stored as an array of true ids only, so treat root false and absent as equal when reconciling.
  - Plot `GlobalPerms` is stored as an object with both true and false.
  - After a restart the shared inner group maps become independent copies, so compare by value, never by identity.
- **Crash risks:**
  - A corrupt JSON escapes `read()` (`IllegalStateException`/`JsonSyntaxException`), and the level fails to load.
  - `read()` walks `data/claims` recursively and runs `UUID.fromString` on every `*.json` name except `!AdminClaims`. Keep backups outside that folder, or give them a non-.json name.
- **`lock_items` updater:** if the config's previous version is below 2, loading sets `lock_items=true` on every claim and plot.
- **No change events exist.** Reconcile by polling on the server thread. Optionally, our own `remap=false` mixin on `Claim#setDirty(Z)V` can act as a change hint.

### 2.5 Still needs a live-server test

1. The server's actual Flan config. No `flan_config.json` exists locally. Check `defaultGroups`, `subClaimsInheritParentDepth`, `defaultClaimDepth`, `minClaimsize`, `permissionLevel`, the config version (for the `lock_items` updater), and `globalDefaultPerms`.
2. `createAdminClaim` with Y = `getMinBuildHeight()` really gives full height; inspect `getDimensions()` in game.
3. District resize route (b) end to end: `getClaimAt` works again, the webmap marker if any, a save/restart round trip, and the plots survive.
4. Plot creation on an empty parent: plots stay independent, both before and after a restart.
5. A never-joined offline-mode UUID member (`nameUUIDFromBytes("OfflinePlayer:"+name)`) gets the right access on first join. The mechanism is confirmed in bytecode, not in game.
6. The modpack's machines (for example Create deployers): which UUID or class they use, whether they turn into `flan:fake_player`, and how the per-plot `fake_player` toggle behaves.
7. `PermissionCheckEvent` listener: the double post inside plots, and cost during large explosions.
8. OP bypass inside plots, and the `/flan bypass` toggle. The toggle is not saved and resets on relog or respawn.
9. Piston and fluid behaviour at plot boundaries inside one district, now guarded by the module (design 22.9); check what players see (client-side ghost blocks, water at the boundary).
10. The `/flan info` stall with many never-joined UUIDs on an offline-mode server. The blocking lookup is confirmed in bytecode; how bad it gets is untested.
11. ~~What the boolean in `ClaimStorage.deleteClaim` does.~~ Settled in bytecode: it also calls `c.remove()` (see 2.1).
12. ~~Save timing versus crash loss: whether to call `save()` after every batch of changes.~~ Settled by design 20.5: no; only after creating, binding or recreating a district claim (skipped under `/save-off`), everything else is rebuilt by the startup reconciliation.

Items 2, 3, 4 and 8 are now covered at the decision level by the real-Flan GameTests (2.6): full height, the resize route, a toJson/fromJson round trip, and the OP bypass (level 2 bypasses, level 1 does not). What is left for the live server is what a player actually sees: the `/flan info` output, being pushed out of a frozen plot, and a real save and restart.

### 2.6 Confirmed by the real-Flan GameTests (2026-09-30)

The dev runtime now loads the approved jar (design 20.1, route ③), and `FlanRealGameTests` (22 tests at first, 28 after the 2026-09-30 review; batch `district_flan_real`, bodies in `FlanRealScenarios` so that the holder's signatures carry no Flan type) exercise the real gateway against it. Facts they pinned down, beyond what the bytecode reading above already said:

- **Loading in dev:** FML's JarJar selector drops a nested jar when a root mod with the same modId exists ("Attempted to select a dependency jar for JarJar which was passed in as source"). So the nested `lingua_bib-1.20.1-1.0.6-forge.jar` (SHA1 `844f0315513541c2f55f8def0295e8d88b767b44`), extracted byte for byte from the approved Flan jar and passed through `fg.deobf` like Flan itself, replaces the SRG-named nested copy. `fg.deobf` renames SRG `@Shadow` members inside mixin classes by name (checked: Flan's `ServerPlayerGameModeMixin.f_9245_` becomes `player`).
- **Dirty flag:** a plot's `setDirty(flag)` goes to its parent (as 2.4 says), and the plot's own `isDirty()` stays at the `true` it got in its constructor forever. Only the root's flag means anything; check "nothing was written" on the parent.
- **Player claim names:** `getClaimName()` on a player-owned claim formats the owner's name in through `ClaimUtils.fetchUsername`, which reads `server.getProfileCache()`. The GameTest server has no profile cache, so it throws a `NullPointerException` there. Admin claims and their plots use the fixed owner name "Admin" and never touch the cache. The gateway's `inspectClaim` therefore reads player claim names defensively.
- **OP level in GameTests:** `GameTestServer.getOperatorUserPermissionLevel()` returns 0, so `PlayerList.op(profile)` gives level 0. Tests put a `ServerOpListEntry` with the level they want into the op list directly.
- **Save round trip:** after `toJson` and `Claim.fromJson`, a root default that was explicitly false comes back absent (-1) and a plot default that was false comes back false. The reconciler treats root false and absent as equal, so a reload causes zero writes. A plot read back resolves its parent lazily through `storage.getFromUUID`.
- **New claims pre-fill defaults:** `new Claim(...)` puts every permission whose `defaultVal` is true into its `globalPerm`, non-global ones included (`can_stay`, `drop`, `enchantment`, `enderchest`, `flight`, `pickup`, `portal`, plus the globals `enderman`, `lock_items`, `snow_golem`). A permission whose default is false has no key on a new root claim, and the reconciler does not add one.
- **Resolution entry points behave as 2.3 says:** a resident with `break` on in the district group cannot break in someone else's plot, and still cannot after being dropped from that plot's groups (they fall to the plot defaults). Plots report -1 for every global permission and follow the parent's value.

### 2.7 Personal claim limit (design 22.20–22.22, 2026-09-30)

No personal Flan claim may be created in a district or within 8 blocks of it, or resized into that zone (OPs excepted). Facts behind the design, from the bytecode of the approved jar and the real-Flan GameTests (`FlanClaimGuardGameTests`, batch `district_claim_guard`):

- **No event to listen to.** On Forge, Flan 1.11.16 only posts `api.forge.PermissionCheckEvent` and `api.forge.ClaimBorderCrossEvent` (`forge.platform.ClaimEventsImpl`); nothing fires when a claim is created or resized. The module therefore injects at the HEAD of `ClaimStorage.createClaim(BlockPos, BlockPos, ServerPlayer)` (golden hoe, normal and 3D; `/flan add`, `add rect`, `add all`) and `ClaimStorage.resizeClaim(Claim, BlockPos from, BlockPos to, ServerPlayer)` (golden-hoe corner drag, `/flan expand`), optional config `miningdim.district.flan.mixins.json`, `remap = false`, `@Pseudo`. Both mixins apply in dev (status 2/2).
- **`resizeClaim` builds its candidate as `new Claim(opposite, to, player.getUUID(), player.serverLevel())`**, so the candidate is never an admin claim and does not carry the claim being resized. The opposite corner is `(minX == from.x ? maxX : minX, minZ == from.z ? maxZ : minZ)`; the GameTests compare our copy of that formula with the box Flan actually produces after each allowed resize. `OtherClaimingModCheck.findConflicts` is not a usable seam for this reason (it cannot tell an OP resizing a district's parent claim from a player).
- **`resizeClaim` on an admin claim** builds `new OfflinePlayerData(server, null)` eagerly (`String.valueOf(null)` → a `null.json` path that does not exist); harmless, and the resize goes through.
- **`claimLandHandling` has a 10-tick click cooldown** (`setClaimActionCooldown`), so a test calls it once per player after setting up the "first click" with the public `setEditingCorner` / `setEditClaim`. A click inside an existing top-level claim without `EDITCLAIM` answers `flan.cantClaimHere` before `createClaim` is reached.
- **`/flan add <from> <to>` uses `BlockPosArgument.getLoadedBlockPos`** (SRG `m_118242_`): both corners must be in loaded chunks, otherwise the command fails with "position is not loaded" before `createClaim`. `/flan add rect` and `/flan expand` use the player's position and facing only. `/flan expand` returns the result of `resizeClaim` (0 when refused).
- **Mock players:** `MockGameTestPlayers.makeMockServerPlayerWithChannel` (anonymous `ServerPlayer` subclass, placed in the player list) works for `createClaim`, `resizeClaim`, `claimLandHandling` and `/flan expand` on the player's own claims: `Claim.canInteract` keeps the permission for the owner before it rewrites anything to `flan:fake_player`. A `ServerPlayer` that is not in the world has no connection, and `createClaim` NPEs when it sends chat.
- **Mixin 0.8.5 accepts `@Coerce Object` on an `@Inject` handler parameter** (`CallbackInjector.Callback.checkDescriptor` looks for the invisible `@Coerce` annotation and calls `Injector.canCoerce`): F2 takes Flan's `Claim` as `Object`, so the mixin package never names Flan's package.
- **Red outline:** `PlayerClaimData.addDisplayClaim(new DisplayBox(minX, level.getMinBuildHeight(), minZ, maxX, level.getMaxBuildHeight(), maxZ), EnumDisplayType.CONFLICT, player.blockPosition().getY())`, best effort (not in the self-check table).
- **Owner and top Y for the listing:** the gateway's `personalClaimsIntersecting` reads `Claim.getOwner()` and `ClaimBox.maxY()`; both were added to the self-check signature table.
- **领地编辑一致性（设计 22.22）**：F2 在其他规则之前核对要改的领地仍是动手的人所在世界里登记着、未删除的那一块；不是就拒，对 OP 和管理员领地同样适用。`Claim.getLevel()` 已加入签名表。细节已私下交接。
