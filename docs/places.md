# Place catalog

Every coordinate the user supplied, identified by cross-checking OpenStreetMap Nominatim reverse geocoding, the Wikipedia geosearch API (10 km) and Open-Meteo elevation/ERA5 climate (raw lookups are in `specs/places.json` under `sources`).
Confidence: **high** = the point is on a named landmark; **medium** = no landmark at the exact point, landscape identified from nearby named features and data; **low** = broad region only.

| n | Place | Confidence | Elev. | Biome | Signature |
|---|---|---|---|---|---|
| 1 | Ushishir Islands (Yankicha, Kraternaya Bay), Russia (Kuril Islands) | high | 145 m | `biomesmod:ushishir_caldera` | a ring of cliffs open to the sea with a bay inside |
| 2 | Krenitsyn Peak in Lake Koltsevoye (Tao-Rusyr caldera), Onekotan, Russia (Kuril Islands) | high | 1028 m | `biomesmod:krenitsyn_caldera` | a cone island standing in a crater lake ringed by cliffs |
| 3 | Alaid volcano (Atlasov Island), Russia (Kuril Islands) | high | 2278 m | `biomesmod:alaid_volcano` | a lone snow-capped cone standing in the open sea |
| 4 | Salar de Uyuni, Bolivia | high | 3654 m | `biomesmod:salar_de_uyuni` | a white plain to the horizon with hexagon cracks and mirror pools |
| 5 | Zhangjiajie National Forest Park (Wulingyuan), China | high | 604 m | `biomesmod:zhangjiajie` | a forest of vertical stone towers with trees on top in mist |
| 6 | Plitvice Lakes National Park, Croatia | high | 819 m | `biomesmod:plitvice_lakes` | turquoise lakes stepping down through forest with falls between them |
| 7 | Lençóis Maranhenses National Park, Brazil | high | 14 m | `biomesmod:lencois_maranhenses` | white dunes with blue lagoons between every crest |
| 8 | Grand Prismatic Spring, Midway Geyser Basin (Yellowstone), United States | high | 2214 m | `biomesmod:grand_prismatic` | a blue pool ringed by rainbow bands of orange and yellow |
| 9 | Iguazu Falls (Devil's Throat), Argentina / Brazil | high | 162 m | `biomesmod:iguazu_falls` | a horseshoe of many waterfalls into a misty gorge in jungle |
| 10 | Southern Patagonian Ice Field above Grey Glacier (Torres del Paine), Chile | medium | 1441 m | `biomesmod:patagonian_icefield` | endless crevassed ice with dark rock peaks poking through |
| 11 | Devils Tower, United States | high | 1386 m | `biomesmod:devils_tower` | one giant ribbed rock tower above grassland |
| 12 | Pamukkale travertines (Hierapolis), Turkey | high | 349 m | `biomesmod:pamukkale` | a white hillside of scalloped pools full of turquoise water |
| 13 | Socotra highlands west of the Diksam plateau, Yemen | medium | 191 m | `biomesmod:socotra` | mushroom-shaped trees standing over pale rocky ground |
| 14 | Monument Valley (Mitten Buttes, Merrick Butte), United States | high | 1638 m | `biomesmod:monument_valley` | red flat-topped buttes with thumb spires on a red plain |
| 15 | Dasht-e Lut (Kaluts yardang field), Iran | medium | 418 m | `biomesmod:dasht_e_lut` | endless parallel ridges like stranded hulls in a burning desert |
| 16 | Daintree Rainforest at Cape Tribulation coast, Australia | high | 23 m | `biomesmod:daintree` | jungle that ends on a white beach by turquoise water |
| 17 | Mount Bromo in the Tengger caldera, Indonesia (East Java) | high | 2252 m | `biomesmod:bromo_tengger` | a grey sand plain inside crater walls with a ribbed cone |
| 18 | Puna de Atacama high plateau, Chile / Argentina | low | 4531 m | `biomesmod:puna_de_atacama` | ochre gravel plain under a dark-blue sky with volcanoes around |
| 19 | Patagonian steppe of Santa Cruz (Corpen Aike), Argentina | high | 215 m | `biomesmod:patagonian_steppe` | golden grass to the horizon over stepped tablelands |
| 20 | duplicate of n=7 | | | | |
| 21 | Denali massif, north flank near Peters Dome and Jeffery Glacier, United States (Alaska) | high | 2295 m | `biomesmod:denali_massif` | ice rivers between towering snow ridges |
| 22 | Hạ Long Bay (Bái Tử Long side, Vân Đồn), Vietnam | high | 0 m | `biomesmod:ha_long_bay` | forested limestone islands with cliff shores, lagoons and floating villages in calm emerald water |
| 23 | Lauterbrunnen Valley (Staubbach Falls), Switzerland | high | 799 m | `biomesmod:lauterbrunnen` | a flat green valley between sheer walls with ribbon waterfalls |
| 24 | Tre Cime di Lavaredo, Italy (Dolomites) | high | 2564 m | `biomesmod:tre_cime` | three pale stone spires in a row above scree and grass |
| 25 | Great Aletsch Glacier at Konkordiaplatz, Switzerland | high | 2691 m | `biomesmod:aletsch_glacier` | a wide white ice river striped with dark moraine lines |
| 26 | Lofoten peaks above Reine (Moskenesøya), Norway | high | 739 m | `biomesmod:lofoten_reine` | jagged peaks standing straight out of the sea |
| 27 | Geirangerfjord at the Seven Sisters falls, Norway | high | 0 m | `biomesmod:geirangerfjord` | a deep narrow fjord with waterfalls dropping straight into it |
| 28 | Reine, Lofoten, Norway | high | 12 m | `biomesmod:lofoten_reine` |  |
| 29 | Southern Sarek National Park (Pårte massif), Sweden (Lapland) | medium | 845 m | `biomesmod:sarek` | glacier-carved valleys through bare tundra mountains |
| 30 | duplicate of n=6 | | | | |
| 31 | Wadi Rum (Jabal Ram), Jordan | high | 953 m | `biomesmod:wadi_rum` | red sand between sheer rounded rock massifs |
| 32 | Dallol hydrothermal field, Danakil Depression, Ethiopia | high | -94 m | `biomesmod:dallol` | a neon yellow and green crust field with acid pools |
| 33 | Salar de Tara (Los Flamencos National Reserve), Chile | high | 4316 m | `biomesmod:salar_de_tara` | a shallow white-rimmed salt lake under ochre hills and rock spires |
| 34 | Grand Canyon (South Rim, Plateau Point), United States | high | 1009 m | `biomesmod:grand_canyon` | a vast stepped canyon of red and cream rock layers |
| 35 | Moraine Lake (Valley of the Ten Peaks, Banff), Canada | high | 1853 m | `biomesmod:moraine_lake` | a vivid turquoise lake under a ring of jagged peaks |
| 36 | Everglades backcountry near Hells Bay, United States (Florida) | high | -1 m | `biomesmod:everglades` | endless shallow water and grass with mangrove islands |
| 37 | Solimões (Amazon) várzea floodplain near Codajás, Brazil | high | 50 m | `biomesmod:amazon_varzea` | a jungle growing out of brown water with lakes and channels |
| 38 | Deadvlei / Sossusvlei, Namib-Naukluft, Namibia | high | 567 m | `biomesmod:sossusvlei` | black dead trees on a white pan under red dunes |
| 39 | Giant's Causeway, United Kingdom (Northern Ireland) | high | 0 m | `biomesmod:giants_causeway` | hexagonal stone steps leading into the sea |
| 40 | Cliffs of Moher (O'Brien's Tower), Ireland | high | 0 m | `biomesmod:cliffs_of_moher` | a wall of layered cliffs dropping into the Atlantic with green grass on top |
| 41 | Central Great Barrier Reef, outer reefs off Townsville, Australia | medium | 0 m | `biomesmod:great_barrier_reef` | turquoise shallows over coral with white sand cays |
| 42 | Lake Gairdner, Australia (South Australia) | high | 119 m | `biomesmod:lake_gairdner` | a blinding white salt lake rimmed by red sand |
| 43 | Alkefjellet bird cliff, Hinlopen Strait (Lomfjordhalvøya, Ny-Friesland), Norway (Svalbard) | high | 2 m | `biomesmod:alkefjellet` | a dark columned sea cliff streaked white and green above icy water |
| 44 | Nansen Island ice cap, Franz Josef Land, Russia | high | 268 m | `biomesmod:franz_josef_land` | a smooth white dome of ice ending in cliffs at the sea |
| 45 | Ross Ice Shelf, Antarctica | high | 41 m | `biomesmod:ross_ice_shelf` | a flat white plain that ends in a sheer ice cliff over the sea |
| 46 | Wright Valley, McMurdo Dry Valleys (Lake Vanda), Antarctica | high | 966 m | `biomesmod:mcmurdo_dry_valleys` | a brown, rocky, snowless valley in Antarctica with a frozen lake |
| 47 | Davis Coast, Graham Land (Antarctic Peninsula), Antarctica | high | 574 m | `biomesmod:antarctic_peninsula` | snow-buried mountains pouring glaciers into a dark sea |
| 48 | Riesco Island and the Magellanic channels, Chile | medium | 236 m | `biomesmod:magellanic_fjords` | windswept beech forest on low hills by grey channels |
| 49 | Cordillera Darwin ice field, Tierra del Fuego, Chile | medium | 1682 m | `biomesmod:cordillera_darwin` | glaciers tumbling from ice-capped mountains down to the sea |
| 50 | East Antarctic Plateau over Lake Vostok, Antarctica | high | 3520 m | `biomesmod:lake_vostok_plateau` | a perfectly empty plain of snow and ice |
| 51 | Orkhon Valley steppe near Kharkhorin, Mongolia | high | 1560 m | `biomesmod:orkhon_steppe` | green hills rolling to the horizon with no trees |
| 52 | Great Exuma and the Exuma Cays (George Town), Bahamas | high | 0 m | `biomesmod:exuma_cays` | white sandbars and tiny islands in glowing turquoise shallows |

## Notes on points without a landmark, duplicates and merges

* **n=2 Krenitsyn Peak in Lake Koltsevoye (Tao-Rusyr caldera), Onekotan**: The point sits on the Krenitsyn cone; Wikipedia geosearch returns no article within 10 km, identification from coordinates of Krenitsyn Peak (49.35 N, 154.70 E).
* **n=6 Plitvice Lakes National Park**: Listed twice in the input (n=6 and n=30): same coordinates.
* **n=7 Lençóis Maranhenses National Park**: Listed twice in the input (n=7 and n=20): same coordinates.
* **n=10 Southern Patagonian Ice Field above Grey Glacier (Torres del Paine)**: No named landmark at the point (1,441 m, ERA5 8.5 m precipitation/yr); it lies on the ice field ~15 km west-northwest of Grey Glacier's front, inside Torres del Paine park.
* **n=13 Socotra highlands west of the Diksam plateau**: No named landmark at the point (191 m); it lies in western-central Socotra between the Hadibu coast and the Diksam plateau, a landscape of limestone wadis and dragon's blood woodland.
* **n=15 Dasht-e Lut (Kaluts yardang field)**: No named landmark at the point (418 m); it lies in the Shahdad district of the central Lut, east of the main Kalut belt toward the Rig-e Yalan sand sea.
* **n=18 Puna de Atacama high plateau**: No named landmark within 10 km (4,531 m); OSM places it in the San Pedro de Atacama commune near the Chile-Argentina border, on the Puna plateau between the Salar de Pular and Salar de Arizaro basins.
* **n=19 Patagonian steppe of Santa Cruz (Corpen Aike)**: No named landmark within 10 km (215 m); the point is open steppe in Corpen Aike department, Santa Cruz.
* **n=26 Lofoten peaks above Reine (Moskenesøya)**: Points n=26 and n=28 are 4 km apart (the ridge above Reine and Reine village on the shore) and describe one landscape; merged into one biome.
* **n=29 Southern Sarek National Park (Pårte massif)**: No named landmark within 10 km (845 m); the point lies in the southern part of Sarek NP, north of Kvikkjokk.
* **n=32 Dallol hydrothermal field, Danakil Depression**: The mod's Nether biome Danakil Flats is a separate reinterpretation; this is the real overworld Dallol.
* **n=33 Salar de Tara (Los Flamencos National Reserve)**: Wikipedia geosearch: Pastos Chicos 6.4 km; coordinates match Salar de Tara (23.03 S, 67.26 W) on the Chile-Argentina border.
* **n=37 Solimões (Amazon) várzea floodplain near Codajás**: No named landmark within 10 km (50 m); the point is on the north-bank floodplain of the Solimões opposite Codajás.
* **n=41 Central Great Barrier Reef, outer reefs off Townsville**: No named feature in OSM or Wikipedia within 10 km; the point is in the reef matrix east of Hinchinbrook Island / Townsville (central GBR).
* **n=43 Alkefjellet bird cliff, Hinlopen Strait (Lomfjordhalvøya, Ny-Friesland)**: The point is on the Lomfjordhalvøya shore of Hinlopenstretet; the user confirmed that the intended place is the Alkefjellet bird cliff on this coast.
* **n=45 Ross Ice Shelf**: No feature names here; the point is on the shelf interior ~250 km from the barrier.
* **n=48 Riesco Island and the Magellanic channels**: Wikipedia geosearch: Riesco Island 9.1 km; the point is on the mainland side of the Seno Otway / Skyring channels.
* **n=49 Cordillera Darwin ice field, Tierra del Fuego**: No named landmark within 10 km (1,682 m, 3.9 m precipitation/yr); the point is on the ice field of the Cordillera Darwin.
* **n=51 Orkhon Valley steppe near Kharkhorin**: Wikipedia geosearch: Shankh Monastery 6.7 km; inside the Orkhon Valley Cultural Landscape (UNESCO).

## Descriptions

* **n=1 Ushishir Islands (Yankicha, Kraternaya Bay)**: A small volcanic island group whose main island Yankicha is a collapsed caldera ring, breached on one side so the sea floods the crater as Kraternaya Bay; steaming fumaroles, seabird cliffs, low tundra. *Landforms:* caldera ring breached to the sea, flooded crater bay, fumaroles.
* **n=2 Krenitsyn Peak in Lake Koltsevoye (Tao-Rusyr caldera), Onekotan**: Onekotan's southern Tao-Rusyr caldera holds the ring-shaped Lake Koltsevoye, with the young Krenitsyn cone (1324 m) rising from the water as an island. *Landforms:* caldera rim, ring lake, central cone island.
* **n=3 Alaid volcano (Atlasov Island)**: The highest Kuril volcano (2339 m): an almost perfect stratovolcano rising straight out of the sea as Atlasov Island, snow-capped, with a summit crater. *Landforms:* symmetric stratovolcano island, summit crater, snow cap.
* **n=4 Salar de Uyuni**: The world's largest salt flat (10,582 km2) at 3,656 m: dead-flat white crust in polygonal hexagons; a thin film of rain water turns it into a sky mirror. *Landforms:* dead-flat pan, hexagonal salt crust, water film.
* **n=5 Zhangjiajie National Forest Park (Wulingyuan)**: Over 3,000 quartz-sandstone pillars, many above 200 m, rise from a misty subtropical forest; their flat tops carry pines. *Landforms:* dense clusters of tall thin sandstone pillars with pine-topped caps, misty gorges.
* **n=6 Plitvice Lakes National Park**: Sixteen terraced lakes in karst, separated by travertine barriers over which countless waterfalls spill; turquoise water inside beech-fir forest. *Landforms:* staircase of turquoise lakes, travertine barriers, waterfalls.
* **n=7 Lençóis Maranhenses National Park**: A coastal field of white quartz dunes up to 40 m; in the rainy season every hollow between the crests fills with blue-green rain lagoons. *Landforms:* white transverse dunes, rain lagoons in the interdunes.
* **n=8 Grand Prismatic Spring, Midway Geyser Basin (Yellowstone)**: The largest hot spring in the US: a deep-blue pool ringed by green, yellow and orange microbial mats that streak outward over white sinter, in lodgepole-pine country. *Landforms:* rainbow hot spring rings, sinter apron, steam, geyser basin in pine forest.
* **n=9 Iguazu Falls (Devil's Throat)**: 275 falls along 2.7 km of a basalt escarpment; the Devil's Throat is a U-shaped chasm where half the river plunges 80 m, in dense Atlantic rainforest with constant spray. *Landforms:* horseshoe basalt escarpment, curtain of falls broken by islets, plunge gorge, spray.
* **n=10 Southern Patagonian Ice Field above Grey Glacier (Torres del Paine)**: West of the Torres del Paine massif the land is buried under the Southern Patagonian Ice Field, which feeds Grey Glacier; 8.5 m of yearly precipitation, crevassed ice and nunataks. *Landforms:* ice field with crevasses and rock nunataks, outlet glaciers.
* **n=11 Devils Tower**: A 265 m igneous butte of hexagonal columns rising above the Belle Fourche River prairie, ringed by a talus apron and ponderosa pines. *Landforms:* single fluted columnar monolith on a talus skirt, prairie and pines.
* **n=12 Pamukkale travertines (Hierapolis)**: A hillside of snow-white travertine terraces built by hot calcium-rich springs: scalloped pools of milky turquoise water stepping down the slope. *Landforms:* white travertine terrace pools on a hillside.
* **n=13 Socotra highlands west of the Diksam plateau**: Socotra's limestone plateaus and wadis carry endemic dragon's blood trees with dense umbrella crowns, bottle trees and frankincense on pale rock. *Landforms:* umbrella-crowned dragon's blood trees on pale limestone plateau.
* **n=14 Monument Valley (Mitten Buttes, Merrick Butte)**: Sandstone buttes up to 300 m stand on red sand plains; the Mittens have a slender 'thumb' spire beside their main block, all on talus aprons of shale. *Landforms:* flat-topped buttes with thumbs, layered sandstone, red sand.
* **n=15 Dasht-e Lut (Kaluts yardang field)**: One of the hottest places on Earth (70 C ground): wind has carved the Lut's silty sediments into the Kaluts, long parallel yardang ridges separated by sandy corridors. *Landforms:* long parallel wind-carved ridges, flat corridors, no life.
* **n=16 Daintree Rainforest at Cape Tribulation coast**: The world's oldest tropical rainforest runs right down to white beaches and fringing reef: buttressed trees, fan palms, strangler figs, mangroves in the creeks. *Landforms:* dense rainforest to the beach, creeks with mangroves.
* **n=17 Mount Bromo in the Tengger caldera**: Inside the 10 km Tengger caldera lies a 'sea of sand' of grey volcanic ash, from which rise the smoking Bromo crater and the perfectly fluted Batok cone. *Landforms:* large caldera, flat grey sand sea, fluted cone.
* **n=18 Puna de Atacama high plateau**: At 4,500 m the Puna is a cold, hyper-dry plateau of ochre gravel plains, salt pans and snow-dusted volcanic cones; vicuñas graze the sparse bunchgrass. *Landforms:* high barren plateau, distant volcanic cones, salt pans.
* **n=19 Patagonian steppe of Santa Cruz (Corpen Aike)**: Wind-scoured, treeless steppe of golden coirón grass and cushion shrubs on stepped basalt-capped mesetas; guanacos, rheas and endless sky. *Landforms:* rolling dry grassland on stepped mesetas, no trees.
* **n=21 Denali massif, north flank near Peters Dome and Jeffery Glacier**: The flanks of North America's highest peak: knife-edge ridges and domes above heavily crevassed glaciers that pour into the Muldrow and Peters basins. *Landforms:* glaciers filling valleys between jagged snowy ridges.
* **n=22 Hạ Long Bay (Bái Tử Long side, Vân Đồn)**: Some 1,600 limestone karst towers and islets, sheer-sided and jungle-capped, rise from emerald water, notched at the waterline by waves. *Landforms:* karst towers in emerald sea, wave notches, vegetated domes.
* **n=23 Lauterbrunnen Valley (Staubbach Falls)**: A textbook glacial trough: flat green floor between 300-500 m limestone cliffs from which 72 waterfalls, Staubbach the best known, drop free into the valley. *Landforms:* U-shaped valley, vertical walls, free-falling waterfalls, meadows.
* **n=24 Tre Cime di Lavaredo**: Three free-standing dolomite towers (up to 2,999 m) with vertical north faces, rising from pale scree fans above alpine meadows. *Landforms:* three sharp pale rock towers on scree.
* **n=25 Great Aletsch Glacier at Konkordiaplatz**: The Alps' largest glacier (23 km): at Konkordiaplatz four ice streams merge into a 900 m thick ice plain, striped by dark medial moraines, below the Jungfrau peaks. *Landforms:* confluence of ice streams, medial moraine stripes, snowfields above.
* **n=26 Lofoten peaks above Reine (Moskenesøya)**: Granite horns over 700 m rise straight from the sea around Reine; red fishing cabins sit on skerries at their feet. *Landforms:* sharp granite horns rising from the sea, skerries, stacks.
* **n=27 Geirangerfjord at the Seven Sisters falls**: A 15 km fjord between near-vertical walls up to 1,400 m; the Seven Sisters waterfalls fall straight into the deep green water. *Landforms:* flooded U-valley (fjord), sheer walls, waterfalls into the sea.
* **n=28 Reine, Lofoten**: Fishing village on skerries at the foot of the Reinebringen horns. *Landforms:* skerries, fjord coast.
* **n=29 Southern Sarek National Park (Pårte massif)**: Sweden's wildest park: alpine tundra, small hanging glaciers and deep U-shaped valleys like Rapadalen, with mountain birch below the treeline. *Landforms:* U-valleys in tundra mountains, small glaciers, birch.
* **n=31 Wadi Rum (Jabal Ram)**: Red sand valleys between towering sandstone and granite inselbergs with smooth domed tops and vertical walls; natural arches and narrow siqs. *Landforms:* red sand plain with steep domed sandstone massifs.
* **n=32 Dallol hydrothermal field, Danakil Depression**: At -125 m, one of the hottest inhabited places on Earth: hot springs dissolve salt and sulfur into acid pools ringed by neon yellow, green and white crusts and salt chimneys. *Landforms:* flat salt plain below sea level, acid pools with yellow/green/white crusts, salt chimneys.
* **n=33 Salar de Tara (Los Flamencos National Reserve)**: A salt lagoon at 4,300 m ringed by ochre hills and wind-carved ignimbrite towers (the 'Monjes de Pacana'); flamingos feed in shallow pink-white brine. *Landforms:* salt lagoon with white rims, ochre hills, eroded rock towers.
* **n=34 Grand Canyon (South Rim, Plateau Point)**: The Colorado River has cut 1.8 km into the Kaibab Plateau: stepped walls of red, buff and grey rock layers, side canyons and buttes, the river far below. *Landforms:* deep stepped canyon with stratified walls, side canyons, river.
* **n=35 Moraine Lake (Valley of the Ten Peaks, Banff)**: A glacier-fed lake of vivid turquoise rock-flour water below the ten peaks' cliffs, dammed by a rockpile, ringed by spruce and larch. *Landforms:* turquoise glacial lake under a wall of peaks, rockpile dam, conifers.
* **n=36 Everglades backcountry near Hells Bay**: Where the 'river of grass' meets the mangrove backcountry: sawgrass marsh, tree islands and a maze of tannin-dark mangrove creeks and bays. *Landforms:* flooded sawgrass with tree islands, mangrove creeks.
* **n=37 Solimões (Amazon) várzea floodplain near Codajás**: Seasonally flooded whitewater forest along the Solimões: a mosaic of oxbow lakes, channels and tall forest standing in water for months. *Landforms:* flooded forest, floodplain lakes and channels.
* **n=38 Deadvlei / Sossusvlei, Namib-Naukluft**: Star dunes over 300 m of rust-red sand surround white clay pans; in Deadvlei the black skeletons of 900-year-old camel thorn trees stand on the cracked white floor. *Landforms:* giant red star dunes, white clay pans, dead black trees.
* **n=39 Giant's Causeway**: About 40,000 interlocking basalt columns, mostly hexagonal, step down from the cliff foot into the sea like paving stones, below dark basalt cliffs and green headland. *Landforms:* polygonal basalt columns stepping into the sea, basalt cliffs.
* **n=40 Cliffs of Moher (O'Brien's Tower)**: Layered sandstone and shale cliffs rise 214 m straight from the Atlantic for 14 km, topped by green grass, with sea stacks such as Branaunmore at their foot. *Landforms:* sheer layered sea cliffs, grass on top, sea stacks.
* **n=41 Central Great Barrier Reef, outer reefs off Townsville**: Platform reefs of the outer central GBR: coral gardens rising to the surface from a 50 m deep shelf, with sand cays, lagoons and very clear water. *Landforms:* coral reef platforms near the surface, lagoons, sand cays.
* **n=42 Lake Gairdner**: A 160 km salt lake in the outback with a crust over a metre thick, bright white against red sand and low granite hills; used for land-speed records. *Landforms:* huge white salt crust, red sand shores, low hills.
* **n=43 Alkefjellet bird cliff, Hinlopen Strait (Lomfjordhalvøya, Ny-Friesland)**: A wall of dark dolerite columns about 100 m high rising straight out of Hinlopenstretet, home to some 60,000 pairs of Brünnich's guillemots; the ledges are streaked white with guano and patched with rust-coloured lichen and green moss, snow lies on the plateau above and ice floes drift below. *Landforms:* vertical columnar dolerite sea cliffs, bird ledges, guano streaks, lichen and moss bands, snowy plateau top.
* **n=44 Nansen Island ice cap, Franz Josef Land**: Basalt islands of Franz Josef Land are buried under domed ice caps that end in ice cliffs at the sea; only black basalt capes poke through. *Landforms:* domed ice cap ending in ice cliffs, basalt capes.
* **n=45 Ross Ice Shelf**: The largest ice shelf on Earth, a floating plain of ice hundreds of metres thick ending at a 50 m ice cliff; utterly flat to the horizon. *Landforms:* flat floating ice plain ending in an ice cliff.
* **n=46 Wright Valley, McMurdo Dry Valleys (Lake Vanda)**: The largest ice-free area of Antarctica: brown gravel and sand valleys with wind-sculpted ventifacts, the frozen Lake Vanda and the Onyx River, under dark dolerite-capped walls. *Landforms:* ice-free brown gravel valley, frozen lakes, dark walls.
* **n=47 Davis Coast, Graham Land (Antarctic Peninsula)**: Alpine peaks and plateau ice of the Antarctic Peninsula spill as crevassed glaciers into fjords and end in ice cliffs; 3 m of snow-equivalent a year. *Landforms:* heavily glaciated mountain coast, ice cliffs, crevasses.
* **n=48 Riesco Island and the Magellanic channels**: Cold, wet hills of lenga and coigüe beech forest, peat bogs and wind-bent trees around the fjords and channels north of the Strait of Magellan. *Landforms:* beech-forested hills, peat bogs, fjord channels.
* **n=49 Cordillera Darwin ice field, Tierra del Fuego**: An ice-capped range rising to 2,500 m out of the Beagle Channel; tidewater glaciers ('Glacier Alley') tumble from the ice field to the sea. *Landforms:* ice field with outlet glaciers to fjords.
* **n=50 East Antarctic Plateau over Lake Vostok**: The coldest place on Earth (-89 C recorded): a featureless ice plateau 3.5 km high, sitting on top of the buried subglacial Lake Vostok. *Landforms:* endless smooth ice plateau, sastrugi.
* **n=51 Orkhon Valley steppe near Kharkhorin**: The Orkhon Valley's rolling grassland at 1,500 m: open steppe with gentle hills, the meandering river and scattered gers, the ancient heart of the Mongol empire. *Landforms:* open rolling grassland, meandering river.
* **n=52 Great Exuma and the Exuma Cays (George Town)**: Hundreds of low limestone cays on the edge of the Great Bahama Bank: sandbars and turquoise shallows a few metres deep, with mangroves and casuarina on the islands. *Landforms:* low sandy cays and sandbars in very shallow turquoise water.
