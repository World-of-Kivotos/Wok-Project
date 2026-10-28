"""Generate production dish metadata from the committed design tables."""
import collections
import csv
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXTRA = {'casualness_delight:fish_and_chips', 'oceansdelight:squid_rings',
         'delightful:crab_rangoon', 'culturaldelights:empanada', 'oceansdelight:honey_fried_kelp'}
DART_EXTRA = {'oceansdelight:squid_rings', 'delightful:crab_rangoon'}
REASSEMBLY = {
    'brewinandchewin:pizza': 'brewinandchewin:pizza_slice',
    'brewinandchewin:quiche': 'brewinandchewin:quiche_slice',
    'casualness_delight:quiche_lorraine': 'casualness_delight:quiche_lorraine_slice',
    'casualness_delight:stargazy_pie': 'casualness_delight:stargazy_pie_slice',
    'delightful:baklava': 'delightful:baklava_slice',
    'delightful:salmonberry_pie': 'delightful:salmonberry_pie_slice',
    'farmersdelight:apple_pie': 'farmersdelight:apple_pie_slice',
    'farmersdelight:chocolate_pie': 'farmersdelight:chocolate_pie_slice',
    'farmersdelight:sweet_berry_cheesecake': 'farmersdelight:sweet_berry_cheesecake_slice',
    'minecraft:cake': 'farmersdelight:cake_slice',
    'minecraft:pumpkin_pie': 'farmersdelight:pumpkin_pie_slice',
}
TYPES = {1: 'miningdim:frying', 2: 'miningdim:baking', 3: 'miningdim:preparing'}
with (ROOT / 'docs/chef-rework/dish-stars.tsv').open(encoding='utf-8-sig', newline='') as f:
    rows = list(csv.DictReader(f, delimiter='\t'))
stars = {row['物品ID']: int(row['星级'].removeprefix('★')) for row in rows}
if len(stars) != len(rows):
    raise ValueError('Duplicate dish ID in star table')
play = json.loads((ROOT / 'tools/chef_mc_practice/source/dish-play.json').read_text(encoding='utf-8'))
if len({d['id'] for d in play}) != len(play):
    raise ValueError('Duplicate dish ID in play table')
dishes = []
differences = []
for d in play:
    if d['id'] not in stars:
        raise ValueError('Missing star: ' + d['id'])
    if d['station'] not in TYPES and d['id'] not in EXTRA:
        continue
    is_extra = d['id'] in EXTRA
    station = 1 if is_extra else d['station']
    if is_extra and d['station'] != 0:
        raise ValueError('Expected fryer candidate to be assigned to cooking pot: ' + d['id'])
    item = {'id':d['id'], 'station':station, 'star':stars[d['id']],
            'motion':(1 if d['id'] in DART_EXTRA else 4) if is_extra else d['motion'], 'flips':0 if is_extra else d['flips'],
            'beats':0 if is_extra else d['beats'], 'drink':False if is_extra else bool(d['drink'])}
    dishes.append(item)
    if d['id'] in REASSEMBLY:
        item['reassemblyItem'] = REASSEMBLY[d['id']]
    if stars[d['id']] != d['star']:
        differences.append({'id':d['id'], 'star_tsv':stars[d['id']], 'star_practice':d['star']})
counts = collections.Counter(d['station'] for d in dishes)
expected = {1:9, 2:37, 3:63}
if len(dishes) != 109 or len({d['id'] for d in dishes}) != 109 or counts != expected:
    raise ValueError('Unexpected target set: ' + repr(counts))
output = ROOT / 'src/main/resources/data/miningdim/chef/production-dishes.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'schemaVersion':1, 'dishes':dishes}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
audit = {'counts':expected, 'original_station_counts':{'frying':4, 'baking':37, 'preparing':63},
         'station_count_note':'stations.md says baking 36 / preparing 64; its category list is not an item-by-item mapping. The practice classification places avocado_toast in baking along with six More Delight toasts, a likely source of the one-dish difference.',
         'additional_frying_ids':sorted(EXTRA), 'star_discrepancies':differences,
         'reassembly_items':REASSEMBLY,
         'disabled_fryer_recipe_id':'casualness_delight:crafting_shaped/deep_frying_pan',
         'disabled_fryer_output_id':'casualness_delight:deep_frying_pan'}
(ROOT / 'tools/chef_recipes/metadata-audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Generated', len(dishes), 'unique dish metadata entries:', dict(counts))
