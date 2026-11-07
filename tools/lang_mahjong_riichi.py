"""Adds the riichi mahjong strings to every language file.

Run from the repository root: python3 tools/lang_mahjong_riichi.py
Each entry is (zh_cn, zh_tw, en_us, ja_jp, ko_kr); zh_hk uses the zh_tw text.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from lang_tools import update  # noqa: E402

YAKU = {
    "riichi": ("立直", "立直", "Riichi", "立直", "리치"),
    "double_riichi": ("两立直", "兩立直", "Double Riichi", "ダブル立直", "더블 리치"),
    "ippatsu": ("一发", "一發", "Ippatsu", "一発", "일발"),
    "menzen_tsumo": ("门前清自摸和", "門前清自摸和", "Fully Concealed Tsumo", "門前清自摸和", "멘젠쯔모"),
    "pinfu": ("平和", "平和", "Pinfu", "平和", "핑후"),
    "tanyao": ("断幺九", "斷么九", "All Simples", "断幺九", "탕야오"),
    "iipeikou": ("一杯口", "一盃口", "Pure Double Sequence", "一盃口", "이페코"),
    "haku": ("役牌：白", "役牌：白", "White Dragon", "役牌 白", "역패: 백"),
    "hatsu": ("役牌：发", "役牌：發", "Green Dragon", "役牌 發", "역패: 발"),
    "chun": ("役牌：中", "役牌：中", "Red Dragon", "役牌 中", "역패: 중"),
    "seat_wind": ("自风", "自風", "Seat Wind", "自風牌", "자풍패"),
    "round_wind": ("场风", "場風", "Round Wind", "場風牌", "장풍패"),
    "haitei": ("海底摸月", "海底摸月", "Last Tile Draw", "海底摸月", "해저로월"),
    "houtei": ("河底捞鱼", "河底撈魚", "Last Discard", "河底撈魚", "하저로어"),
    "rinshan": ("岭上开花", "嶺上開花", "After a Kong", "嶺上開花", "영상개화"),
    "chankan": ("抢杠", "搶槓", "Robbing a Kong", "槍槓", "창깡"),
    "chiitoitsu": ("七对子", "七對子", "Seven Pairs", "七対子", "치또이쯔"),
    "chanta": ("混全带幺九", "混全帶么九", "Half Outside Hand", "混全帯幺九", "찬타"),
    "ittsu": ("一气通贯", "一氣通貫", "Pure Straight", "一気通貫", "일기통관"),
    "sanshoku": ("三色同顺", "三色同順", "Mixed Triple Sequence", "三色同順", "삼색동순"),
    "sanshoku_doukou": ("三色同刻", "三色同刻", "Triple Triplets", "三色同刻", "삼색동각"),
    "sankantsu": ("三杠子", "三槓子", "Three Kongs", "三槓子", "산깡쯔"),
    "toitoi": ("对对和", "對對和", "All Triplets", "対々和", "또이또이"),
    "sanankou": ("三暗刻", "三暗刻", "Three Concealed Triplets", "三暗刻", "산안커"),
    "shousangen": ("小三元", "小三元", "Little Three Dragons", "小三元", "소삼원"),
    "honroutou": ("混老头", "混老頭", "All Terminals and Honours", "混老頭", "혼노두"),
    "ryanpeikou": ("二杯口", "二盃口", "Twice Pure Double Sequence", "二盃口", "량페코"),
    "junchan": ("纯全带幺九", "純全帶么九", "Fully Outside Hand", "純全帯幺九", "준찬타"),
    "honitsu": ("混一色", "混一色", "Half Flush", "混一色", "혼일색"),
    "chinitsu": ("清一色", "清一色", "Full Flush", "清一色", "청일색"),
    "nagashi_mangan": ("流局满贯", "流局滿貫", "Mangan at Draw", "流し満貫", "유국만관"),
    "kokushi": ("国士无双", "國士無雙", "Thirteen Orphans", "国士無双", "국사무쌍"),
    "suuankou": ("四暗刻", "四暗刻", "Four Concealed Triplets", "四暗刻", "스안커"),
    "daisangen": ("大三元", "大三元", "Big Three Dragons", "大三元", "대삼원"),
    "shousuushii": ("小四喜", "小四喜", "Little Four Winds", "小四喜", "소사희"),
    "daisuushii": ("大四喜", "大四喜", "Big Four Winds", "大四喜", "대사희"),
    "tsuuiisou": ("字一色", "字一色", "All Honours", "字一色", "자일색"),
    "ryuuiisou": ("绿一色", "綠一色", "All Green", "緑一色", "녹일색"),
    "chinroutou": ("清老头", "清老頭", "All Terminals", "清老頭", "청노두"),
    "suukantsu": ("四杠子", "四槓子", "Four Kongs", "四槓子", "스깡쯔"),
    "chuuren": ("九莲宝灯", "九蓮寶燈", "Nine Gates", "九蓮宝燈", "구련보등"),
    "tenhou": ("天和", "天和", "Blessing of Heaven", "天和", "천화"),
    "chiihou": ("地和", "地和", "Blessing of Earth", "地和", "지화"),
}

# Yaku by han, for the list; an open hand counts one han fewer where marked.
GROUPS = {
    "1": ["riichi", "ippatsu", "menzen_tsumo", "pinfu", "tanyao", "iipeikou", "haku", "hatsu", "chun", "seat_wind", "round_wind", "haitei", "houtei", "rinshan", "chankan"],
    "2": ["double_riichi", "chiitoitsu", "chanta", "ittsu", "sanshoku", "sanshoku_doukou", "sankantsu", "toitoi", "sanankou", "shousangen", "honroutou"],
    "3": ["ryanpeikou", "junchan", "honitsu"],
    "6": ["chinitsu"],
    "yakuman": ["kokushi", "suuankou", "daisangen", "shousuushii", "daisuushii", "tsuuiisou", "ryuuiisou", "chinroutou", "suukantsu", "chuuren", "tenhou", "chiihou"],
}
HEADS = {
    "1": ("1 番：", "1 番：", "1 han: ", "1 翻：", "1판: "),
    "2": ("2 番：", "2 番：", "2 han: ", "2 翻：", "2판: "),
    "3": ("3 番：", "3 番：", "3 han: ", "3 翻：", "3판: "),
    "6": ("6 番：", "6 番：", "6 han: ", "6 翻：", "6판: "),
    "yakuman": ("役满：", "役滿：", "Yakuman: ", "役満：", "역만: "),
}
SEP = ("、", "、", ", ", "・", ", ")
END = ("。", "。", ".", "。", ".")

S = {}


def k(key, *texts):
    assert len(texts) == 5, key
    S[key] = texts


k("tukuai.game.riichi", "立直麻将", "立直麻將", "Riichi Mahjong", "リーチ麻雀", "리치 마작")
k("tukuai.preset.riichi.east", "东风战（有赤宝牌）", "東風戰（有赤寶牌）", "East round, red fives", "東風戦（赤あり）", "동풍전 (적도라 있음)")
k("tukuai.preset.riichi.south", "半庄战（有赤宝牌）", "半莊戰（有赤寶牌）", "East and south, red fives", "半荘戦（赤あり）", "반장전 (적도라 있음)")
k("tukuai.preset.riichi.east_no_red", "东风战（无赤宝牌）", "東風戰（無赤寶牌）", "East round, no red fives", "東風戦（赤なし）", "동풍전 (적도라 없음)")
for key, names in YAKU.items():
    k(f"tukuai.riichi.yaku.{key}", *names)
k("tukuai.riichi.han", "%s 番", "%s 番", "%s han", "%s 翻", "%s판")
k("tukuai.riichi.han_fu", "%s 番 %s 符", "%s 番 %s 符", "%s han %s fu", "%s 翻 %s 符", "%s판 %s부")
for key, texts in {
    "mangan": ("满贯", "滿貫", "Mangan", "満貫", "만관"),
    "haneman": ("跳满", "跳滿", "Haneman", "跳満", "하네만"),
    "baiman": ("倍满", "倍滿", "Baiman", "倍満", "배만"),
    "sanbaiman": ("三倍满", "三倍滿", "Sanbaiman", "三倍満", "삼배만"),
    "kazoe_yakuman": ("累计役满", "累計役滿", "Counted Yakuman", "数え役満", "헤아림 역만"),
    "yakuman": ("役满", "役滿", "Yakuman", "役満", "역만"),
}.items():
    k(f"tukuai.riichi.limit.{key}", *texts)
k("tukuai.riichi.multiple_yakuman", "%s 倍役满", "%s 倍役滿", "%s× yakuman", "%s 倍役満", "%s배 역만")
k("tukuai.riichi.points", "%s 点", "%s 點", "%s points", "%s 点", "%s점")
k("tukuai.riichi.points_now", "持点", "持點", "Points", "持ち点", "점수")
k("tukuai.riichi.dora", "宝牌", "寶牌", "Dora", "ドラ", "도라")
k("tukuai.riichi.aka", "赤宝牌", "赤寶牌", "Red fives", "赤ドラ", "적도라")
k("tukuai.riichi.ura", "里宝牌", "裏寶牌", "Ura dora", "裏ドラ", "뒷도라")
k("tukuai.riichi.dora_indicators", "宝牌指示牌", "寶牌指示牌", "Dora indicators", "ドラ表示牌", "도라 표시패")
k("tukuai.riichi.ura_indicators", "里宝牌指示牌", "裏寶牌指示牌", "Ura indicators", "裏ドラ表示牌", "뒷도라 표시패")
k("tukuai.riichi.riichi", "立直", "立直", "Riichi", "リーチ", "리치")
k("tukuai.riichi.riichi_cancel", "取消立直", "取消立直", "Cancel riichi", "リーチをやめる", "리치 취소")
k("tukuai.riichi.riichi_discard", "立直并打出", "立直並打出", "Riichi and discard", "リーチして捨てる", "리치하고 버리기")
k("tukuai.riichi.tsumo_with", "自摸（%s）", "自摸（%s）", "Tsumo (%s)", "ツモ（%s）", "쯔모 (%s)")
k("tukuai.riichi.ron", "荣和", "榮和", "Ron", "ロン", "론")
k("tukuai.riichi.ron_with", "荣和（%s）", "榮和（%s）", "Ron (%s)", "ロン（%s）", "론 (%s)")
k("tukuai.riichi.abort", "九种九牌", "九種九牌", "Nine terminals", "九種九牌", "구종구패")
k("tukuai.riichi.furiten", "振听", "振聽", "Furiten", "フリテン", "후리텐")
k("tukuai.riichi.no_yaku", "无役", "無役", "no yaku", "役なし", "역 없음")
k("tukuai.riichi.no_yaku_waits", "所听的牌均无役或振听", "所聽的牌均無役或振聽", "none has a yaku you can win with", "どの待ちも役がないかフリテン", "어느 대기패도 역이 없거나 후리텐")
k("tukuai.riichi.live_wall", "余牌 %s", "餘牌 %s", "Tiles left: %s", "残り %s 枚", "남은 패 %s")
k("tukuai.riichi.round", "%s %s 局 %s 本场 · 供托 %s", "%s %s 局 %s 本場 · 供託 %s", "%s %s, %s honba, %s sticks", "%s%s局 %s本場・供託 %s", "%s %s국 %s본장 · 공탁 %s")
k("tukuai.riichi.hand_title", "%s %s 局 %s 本场", "%s %s 局 %s 本場", "%s %s, %s honba", "%s%s局 %s本場", "%s %s국 %s본장")
k("tukuai.riichi.won_tsumo", "%s 自摸和牌", "%s 自摸和牌", "%s won by tsumo", "%s のツモ和了", "%s 님 쯔모 화료")
k("tukuai.riichi.won_ron", "%s 荣和 %s 打出的牌", "%s 榮和 %s 打出的牌", "%s won on %s's discard", "%s が %s からロン", "%s 님이 %s 님에게서 론")
k("tukuai.riichi.exhausted", "流局", "流局", "Exhaustive draw", "流局", "유국")
k("tukuai.riichi.nobody_ready", "所有人均未听牌。", "所有人均未聽牌。", "Nobody was ready.", "全員ノーテンです。", "모두 노텐입니다.")
k("tukuai.riichi.ready_list", "听牌：%s", "聽牌：%s", "Ready: %s", "テンパイ：%s", "텐파이: %s")
k("tukuai.riichi.nagashi", "%s 流局满贯", "%s 流局滿貫", "%s made a mangan at the draw", "%s 流し満貫", "%s 님 유국만관")
k("tukuai.riichi.abort_note", "本局中途流局，庄家连庄，本场数加一。", "本局中途流局，莊家連莊，本場數加一。",
  "The hand was called off. The dealer keeps the deal, with one more honba.", "途中流局です。親は連荘し、本場が 1 つ増えます。", "도중 유국입니다. 선은 연장하고 본장이 하나 늘어납니다.")
for key, texts in {
    "nine_terminals": ("九种九牌流局", "九種九牌流局", "Nine terminals: hand called off", "九種九牌で流局", "구종구패 유국"),
    "four_winds": ("四风连打流局", "四風連打流局", "Four winds: hand called off", "四風連打で流局", "사풍연타 유국"),
    "four_riichi": ("四家立直流局", "四家立直流局", "Four riichi: hand called off", "四家立直で流局", "사가리치 유국"),
    "four_kongs": ("四杠散了", "四槓散了", "Four kongs: hand called off", "四槓散了", "사깡유국"),
}.items():
    k(f"tukuai.riichi.abort.{key}", *texts)
k("tukuai.riichi.note.can_tsumo", "可以自摸和牌（%s），也可以打出一张牌继续", "可以自摸和牌（%s），也可以打出一張牌繼續",
  "You can win by tsumo (%s), or discard to play on", "ツモ和了できます（%s）。牌を捨てて続けることもできます", "쯔모로 화료할 수 있습니다 (%s). 패를 버리고 계속할 수도 있습니다")
k("tukuai.riichi.note.can_ron", "可以荣和这张牌（%s）", "可以榮和這張牌（%s）", "You can win on this tile (%s)", "この牌でロンできます（%s）", "이 패로 론할 수 있습니다 (%s)")
k("tukuai.riichi.note.pick_riichi", "请选择立直时打出的牌：只能选择打出后仍然听牌的牌", "請選擇立直時打出的牌：只能選擇打出後仍然聽牌的牌",
  "Pick the tile to declare riichi with: only tiles that leave you ready", "リーチ宣言牌を選んでください。捨てても聴牌のままの牌だけを選べます", "리치 선언패를 고르세요. 버려도 텐파이가 유지되는 패만 고를 수 있습니다")
k("tukuai.riichi.note.after_riichi", "已立直：打出摸到的牌", "已立直：打出摸到的牌", "In riichi: discard the tile you drew", "リーチ中：引いた牌を捨てます", "리치 중: 뽑은 패를 버립니다")

G = "tukuai.guide.riichi"
k(f"{G}.table", "本桌规则", "本桌規則", "This table", "この卓のルール", "이 테이블의 규칙")
k(f"{G}.table.east", "东风战：只打东场四局（庄家连庄时局数增加）。", "東風戰：只打東場四局（莊家連莊時局數增加）。",
  "East round only: four hands, more while the dealer keeps the deal.", "東風戦：東場の 4 局だけを行います（連荘すると局数が増えます）。", "동풍전: 동장 4국만 합니다 (연장하면 국수가 늘어납니다).")
k(f"{G}.table.south", "半庄战：打东场和南场各四局（庄家连庄时局数增加）。", "半莊戰：打東場和南場各四局（莊家連莊時局數增加）。",
  "East and south rounds: four hands each, more while the dealer keeps the deal.", "半荘戦：東場と南場を 4 局ずつ行います（連荘すると局数が増えます）。", "반장전: 동장과 남장을 4국씩 합니다 (연장하면 국수가 늘어납니다).")
k(f"{G}.table.reds.on", "每门序数牌中各有一张红色的 5，算作宝牌。", "每門序數牌中各有一張紅色的 5，算作寶牌。",
  "One five of each suit is red and counts as a dora.", "各数牌に赤い 5 が 1 枚ずつあり、ドラになります。", "수패마다 빨간 5가 한 장씩 있으며, 도라가 됩니다.")
k(f"{G}.table.reds.off", "不使用赤宝牌。", "不使用赤寶牌。", "No red fives.", "赤ドラはありません。", "적도라가 없습니다.")
k(f"{G}.table.points", "每人起始 %s 点。", "每人起始 %s 點。", "Everyone starts with %s points.", "持ち点は各 %s 点から始まります。", "모두 %s점으로 시작합니다.")
k(f"{G}.table.bust.on", "有人点数低于零时，一场立即结束。", "有人點數低於零時，一場立即結束。", "The game ends at once when someone falls below zero.", "誰かの持ち点がマイナスになると、その時点で終了します。", "누군가의 점수가 0 미만이 되면 그 즉시 끝납니다.")
k(f"{G}.table.bust.off", "点数低于零时继续进行。", "點數低於零時繼續進行。", "Play goes on below zero.", "持ち点がマイナスでも続行します。", "점수가 0 미만이어도 계속합니다.")
k(f"{G}.table.kuitan.on", "吃、碰后仍可计断幺九。", "吃、碰後仍可計斷么九。", "All simples counts in an open hand.", "鳴いても断幺九が成立します（喰いタンあり）。", "울어도 탕야오가 성립합니다 (쿠이탕 있음).")
k(f"{G}.table.kuitan.off", "吃、碰后不计断幺九。", "吃、碰後不計斷么九。", "All simples needs a closed hand.", "鳴くと断幺九は成立しません（喰いタンなし）。", "울면 탕야오가 성립하지 않습니다 (쿠이탕 없음).")

k(f"{G}.goal", "玩法简介", "玩法簡介", "The game", "遊び方", "게임 소개")
k(f"{G}.goal.1", "四人日本麻将。目标是用 14 张牌凑成一手和牌，并且这手牌至少有一个役。",
  "四人日本麻將。目標是用 14 張牌湊成一手和牌，並且這手牌至少有一個役。",
  "Japanese mahjong for four players. The aim is to make a winning hand of 14 tiles that has at least one yaku.",
  "4 人で遊ぶ日本の麻雀です。14 枚で和了の形を作り、その手に役が 1 つ以上あることを目指します。",
  "4인용 일본 마작입니다. 14장으로 화료 형태를 만들고, 그 패에 역이 하나 이상 있도록 하는 것이 목표입니다.")
k(f"{G}.goal.2", "每人起始有相同的点数。和牌时从其他玩家处得到点数，点数多少取决于这手牌的番数和符数。",
  "每人起始有相同的點數。和牌時從其他玩家處得到點數，點數多少取決於這手牌的番數和符數。",
  "Everyone starts with the same points. A win takes points from the others; how many depends on the hand's han and fu.",
  "全員が同じ持ち点から始めます。和了すると他のプレイヤーから点を受け取ります。点数は手の翻と符で決まります。",
  "모두 같은 점수로 시작합니다. 화료하면 다른 플레이어에게서 점수를 받으며, 점수는 패의 판수와 부수로 정해집니다.")
k(f"{G}.goal.3", "一场结束时，点数最多的玩家获胜。", "一場結束時，點數最多的玩家獲勝。", "When the game ends, whoever has the most points wins.", "終了時に持ち点が最も多い人の勝ちです。", "끝났을 때 점수가 가장 많은 사람이 이깁니다.")

k(f"{G}.tiles", "牌", "牌", "Tiles", "牌", "패")
k(f"{G}.tiles.1", "共 136 张牌：万、筒、条三门序数牌各有 1 至 9，另有四种风牌和三种箭牌，每种各四张。没有花牌。",
  "共 136 張牌：萬、筒、條三門序數牌各有 1 至 9，另有四種風牌和三種箭牌，每種各四張。沒有花牌。",
  "136 tiles: three numbered suits from 1 to 9, four winds and three dragons, four of each. There are no flowers.",
  "全部で 136 枚です。萬子・筒子・索子が 1 から 9 まで、風牌 4 種、三元牌 3 種があり、各 4 枚です。花牌はありません。",
  "모두 136장입니다. 만수·통수·삭수가 1부터 9까지, 풍패 4종, 삼원패 3종이 있으며 각각 4장입니다. 화패는 없습니다.")
k(f"{G}.tiles.characters", "万", "萬", "Characters", "萬子", "만수")
k(f"{G}.tiles.dots", "筒", "筒", "Dots", "筒子", "통수")
k(f"{G}.tiles.bamboo", "条", "條", "Bamboo", "索子", "삭수")
k(f"{G}.tiles.winds", "风牌", "風牌", "Winds", "風牌", "풍패")
k(f"{G}.tiles.winds.text", "东、南、西、北。风牌不能组成顺子。", "東、南、西、北。風牌不能組成順子。", "East, south, west and north. Winds make no runs.", "東・南・西・北。順子にはなりません。", "동·남·서·북. 슌쯔를 만들 수 없습니다.")
k(f"{G}.tiles.dragons", "箭牌", "箭牌", "Dragons", "三元牌", "삼원패")
k(f"{G}.tiles.dragons.text", "中、发、白。箭牌也不能组成顺子。", "中、發、白。箭牌也不能組成順子。", "Red, green and white. Dragons make no runs either.", "中・發・白。これも順子にはなりません。", "중·발·백. 이것도 슌쯔를 만들 수 없습니다.")
k(f"{G}.tiles.red", "赤宝牌", "赤寶牌", "Red fives", "赤ドラ", "적도라")
k(f"{G}.tiles.red.text", "使用赤宝牌时，每门各有一张红色的 5。它与普通的 5 用法相同，和牌时额外计 1 番。",
  "使用赤寶牌時，每門各有一張紅色的 5。它與普通的 5 用法相同，和牌時額外計 1 番。",
  "At tables with red fives, one five of each suit is red. It plays as any five, and adds 1 han to a winning hand.",
  "赤ドラありの卓では、各数牌の 5 のうち 1 枚が赤です。普通の 5 と同じように使え、和了すると 1 翻加わります。",
  "적도라가 있는 테이블에서는 수패마다 5 중 한 장이 빨갛습니다. 보통 5와 똑같이 쓰며, 화료하면 1판이 더해집니다.")

k(f"{G}.sets", "和牌的组成", "和牌的組成", "Winning hands", "和了の形", "화료의 형태")
k(f"{G}.sets.1", "一般的和牌由四组牌加一对将组成。", "一般的和牌由四組牌加一對將組成。", "A winning hand is usually four sets and a pair.", "和了の形は、ふつう 4 つの面子と 1 つの雀頭です。", "화료 형태는 보통 몸통 네 개와 머리 하나입니다.")
k(f"{G}.sets.chow", "顺子", "順子", "Chow", "順子", "슌쯔")
k(f"{G}.sets.chow.text", "同一门序数牌中点数相连的三张牌。", "同一門序數牌中點數相連的三張牌。", "Three tiles in a row of one suit.", "同じ種類の数牌で、数が続いた 3 枚。", "같은 수패에서 숫자가 이어지는 세 장.")
k(f"{G}.sets.pung", "刻子", "刻子", "Pung", "刻子", "커쯔")
k(f"{G}.sets.pung.text", "三张相同的牌。", "三張相同的牌。", "Three of the same tile.", "同じ牌 3 枚。", "같은 패 세 장.")
k(f"{G}.sets.kong", "杠子", "槓子", "Kong", "槓子", "깡쯔")
k(f"{G}.sets.kong.text", "四张相同的牌。它算作一组，杠后从王牌补摸一张，并翻开一张新的宝牌指示牌。",
  "四張相同的牌。它算作一組，槓後從王牌補摸一張，並翻開一張新的寶牌指示牌。",
  "Four of the same tile. It counts as one set; its maker draws from the dead wall, and a new dora indicator is turned up.",
  "同じ牌 4 枚。1 つの面子として数え、王牌から 1 枚補充し、新しいドラ表示牌をめくります。",
  "같은 패 네 장. 몸통 하나로 치며, 왕패에서 한 장을 보충하고 새 도라 표시패를 뒤집습니다.")
k(f"{G}.sets.pair", "雀头", "雀頭", "Pair", "雀頭", "머리")
k(f"{G}.sets.pair.text", "两张相同的牌。", "兩張相同的牌。", "Two of the same tile.", "同じ牌 2 枚。", "같은 패 두 장.")
k(f"{G}.sets.example", "一手和牌：四组牌加一对雀头。", "一手和牌：四組牌加一對雀頭。", "A winning hand: four sets and a pair.", "和了の例：4 面子 1 雀頭。", "화료 예: 몸통 넷과 머리 하나.")
k(f"{G}.sets.2", "此外还有两种特殊的和牌形式，都只能在没有吃、碰、明杠时组成：", "此外還有兩種特殊的和牌形式，都只能在沒有吃、碰、明槓時組成：",
  "There are also two special winning hands, which may only be made with nothing claimed:", "このほか、鳴いていないときだけ作れる特殊な形が 2 つあります。", "그 밖에 울지 않았을 때만 만들 수 있는 특수한 형태가 두 가지 있습니다.")
k(f"{G}.shape.chiitoitsu", "七个互不相同的对子。", "七個互不相同的對子。", "Seven different pairs.", "7 種類の異なる対子。", "서로 다른 또이쯔 일곱 개.")
k(f"{G}.shape.kokushi", "三门序数牌的一和九与七种字牌各一张，其中一种再多一张。", "三門序數牌的一和九與七種字牌各一張，其中一種再多一張。",
  "One each of the ones, nines and honours, and a second of one of them.", "数牌の 1 と 9、字牌 7 種を 1 枚ずつ、そのうち 1 種をもう 1 枚。", "수패의 1과 9, 자패 7종을 한 장씩, 그중 하나를 한 장 더.")

k(f"{G}.turn", "轮到你时", "輪到你時", "Your turn", "自分の番", "내 차례")
k(f"{G}.turn.1", "每人 13 张牌，庄家先摸牌。轮到你时，从牌墙摸一张牌，再打出一张。点击一张牌选中，再次点击即可打出。",
  "每人 13 張牌，莊家先摸牌。輪到你時，從牌牆摸一張牌，再打出一張。點擊一張牌選中，再次點擊即可打出。",
  "Everyone has 13 tiles and the dealer draws first. On your turn, draw a tile, then discard one: click a tile to pick it and click it again to discard it.",
  "各自 13 枚を持ち、親から引きます。自分の番では山から 1 枚引いて 1 枚捨てます。牌をクリックで選び、もう一度クリックで捨てます。",
  "각자 13장을 가지며 선부터 뽑습니다. 내 차례에는 한 장을 뽑고 한 장을 버립니다. 패를 클릭해 고르고 한 번 더 클릭하면 버립니다.")
k(f"{G}.turn.2", "摸到的牌使你凑成有役的和牌时，可以自摸和牌。", "摸到的牌使你湊成有役的和牌時，可以自摸和牌。",
  "When the tile you drew completes a hand with a yaku, you may win by tsumo.", "引いた牌で役のある和了形が完成したら、ツモ和了できます。", "뽑은 패로 역이 있는 화료 형태가 완성되면 쯔모로 화료할 수 있습니다.")
k(f"{G}.turn.3", "手中有四张相同的牌，或摸到已碰的牌的第四张时，可以杠牌。", "手中有四張相同的牌，或摸到已碰的牌的第四張時，可以槓牌。",
  "With four of a tile in hand, or the fourth of a pung, you may make a kong.", "同じ牌を 4 枚持つか、ポンした牌の 4 枚目を引いたら、カンできます。", "같은 패 네 장을 가졌거나 퐁한 패의 네 번째 장을 뽑으면 깡할 수 있습니다.")
k(f"{G}.turn.4", "牌墙最后 14 张为王牌，不能摸。可摸的牌摸完时本局流局。", "牌牆最後 14 張為王牌，不能摸。可摸的牌摸完時本局流局。",
  "The last 14 tiles of the wall are the dead wall and are not drawn. When the rest runs out, the hand is a draw.",
  "山の最後の 14 枚は王牌で、引くことはできません。それ以外の牌が尽きると流局です。",
  "패산 마지막 14장은 왕패라 뽑을 수 없습니다. 나머지 패가 다 떨어지면 유국입니다.")

k(f"{G}.claims", "吃、碰、杠与荣和", "吃、碰、槓與榮和", "Claiming a discard", "鳴きとロン", "울기와 론")
k(f"{G}.claims.1", "其他玩家打出牌时，你可以要这张牌：", "其他玩家打出牌時，你可以要這張牌：", "When another player discards, you may claim the tile:", "ほかのプレイヤーが牌を捨てたとき、その牌を取ることができます。", "다른 플레이어가 패를 버리면 그 패를 가져올 수 있습니다.")
k(f"{G}.claims.chow", "吃", "吃", "Chow", "チー", "치")
k(f"{G}.claims.chow.text", "只能吃上家打出的牌，组成顺子后打出一张。", "只能吃上家打出的牌，組成順子後打出一張。", "Only from the player before you: make a run, then discard.", "上家の捨て牌だけをチーできます。順子を作って 1 枚捨てます。", "상가가 버린 패만 치할 수 있습니다. 슌쯔를 만들고 한 장 버립니다.")
k(f"{G}.claims.pung", "碰", "碰", "Pung", "ポン", "퐁")
k(f"{G}.claims.pung.text", "手中有两张相同的牌时，组成刻子后打出一张。", "手中有兩張相同的牌時，組成刻子後打出一張。", "With two of the tile in hand: make a pung, then discard.", "同じ牌が 2 枚あれば刻子を作って 1 枚捨てます。", "같은 패 두 장이 있으면 커쯔를 만들고 한 장 버립니다.")
k(f"{G}.claims.kong", "杠", "槓", "Kong", "カン", "깡")
k(f"{G}.claims.kong.text", "手中有三张相同的牌时，组成杠子并补摸一张。", "手中有三張相同的牌時，組成槓子並補摸一張。", "With three of the tile in hand: make a kong and draw a replacement.", "同じ牌が 3 枚あれば槓子を作り、1 枚補充します。", "같은 패 세 장이 있으면 깡쯔를 만들고 한 장 보충합니다.")
k(f"{G}.claims.2", "荣和：这张牌使你凑成有役的和牌，并且你没有振听。", "榮和：這張牌使你湊成有役的和牌，並且你沒有振聽。",
  "Ron: the tile completes your hand with a yaku, and you are not furiten.", "ロン：その牌で役のある和了形が完成し、フリテンでないとき。", "론: 그 패로 역이 있는 화료 형태가 완성되고, 후리텐이 아닐 때.")
k(f"{G}.claims.3", "荣和优先于碰和杠，碰和杠优先于吃。多人可以同时荣和，本场和供托归打牌者之后最近的一家。",
  "榮和優先於碰和槓，碰和槓優先於吃。多人可以同時榮和，本場和供託歸打牌者之後最近的一家。",
  "Ron comes before pung and kong, and those before chow. Several players may win on one tile; honba and riichi sticks go to the one nearest after the discarder.",
  "ロンはポン・カンより、ポン・カンはチーより優先します。複数人が同時にロンでき、本場と供託は捨てた人から順番が近い人が受け取ります。",
  "론이 퐁·깡보다, 퐁·깡이 치보다 우선합니다. 여러 명이 동시에 론할 수 있으며, 본장과 공탁은 버린 사람 다음 순서에서 가장 가까운 사람이 받습니다.")
k(f"{G}.claims.4", "吃、碰后，不能立即打出与所吃、所碰相同的牌，吃的牌也不能打出能组成同一顺子的另一端（不能食替）。",
  "吃、碰後，不能立即打出與所吃、所碰相同的牌，吃的牌也不能打出能組成同一順子的另一端（不能食替）。",
  "Right after a claim you may not discard the tile you claimed, nor, after a chow, the tile at the other end of the same line.",
  "鳴いた直後に、鳴いた牌と同じ牌や、チーした順子の反対側の牌を捨てることはできません（喰い替えなし）。",
  "운 직후에는 가져온 패와 같은 패나, 치한 슌쯔의 반대쪽 패를 버릴 수 없습니다 (쿠이카에 없음).")
k(f"{G}.claims.5", "吃、碰或明杠后，这手牌不再是门前清，部分役不能计算或番数减少。", "吃、碰或明槓後，這手牌不再是門前清，部分役不能計算或番數減少。",
  "After a claim the hand is open: some yaku no longer count, and some count one han fewer.", "鳴くと門前でなくなり、成立しない役や翻数が下がる役があります。", "울면 멘젠이 아니게 되어, 성립하지 않거나 판수가 줄어드는 역이 있습니다.")

k(f"{G}.yaku", "役", "役", "Yaku", "役", "역")
k(f"{G}.yaku.1", "和牌至少需要一个役。宝牌只增加番数，本身不算役。", "和牌至少需要一個役。寶牌只增加番數，本身不算役。",
  "A winning hand needs at least one yaku. Dora add han but are not a yaku themselves.", "和了には役が 1 つ以上必要です。ドラは翻を増やすだけで、それ自体は役ではありません。", "화료하려면 역이 하나 이상 필요합니다. 도라는 판수만 늘릴 뿐 역이 아닙니다.")
k(f"{G}.yaku.2", "以下是几种常见的役：", "以下是幾種常見的役：", "Some common yaku:", "よくある役をいくつか挙げます。", "흔한 역 몇 가지입니다.")
for key, texts in {
    "tanyao": ("1 番：全部为 2 至 8 的序数牌。", "1 番：全部為 2 至 8 的序數牌。", "1 han: only numbered tiles from 2 to 8.", "1 翻：2 から 8 の数牌だけ。", "1판: 2~8 수패만."),
    "pinfu": ("1 番：门前清，四组都是顺子，雀头不是役牌，并且听两面。", "1 番：門前清，四組都是順子，雀頭不是役牌，並且聽兩面。",
              "1 han: closed, four runs, a pair that is no value tile, waiting on either end of a run.", "1 翻：門前で 4 面子すべて順子、雀頭が役牌でなく、両面待ち。", "1판: 멘젠, 몸통 넷이 모두 슌쯔, 머리가 역패가 아니고 양면 대기."),
    "haku": ("1 番：中、发、白，或自己的门风、场风的刻子，每组各 1 番。", "1 番：中、發、白，或自己的門風、場風的刻子，每組各 1 番。",
             "1 han: a pung of a dragon, or of your seat wind or the round's wind, each.", "1 翻：三元牌、または自風・場風の刻子 1 つにつき。", "1판: 삼원패, 또는 자풍·장풍 커쯔 하나마다."),
    "iipeikou": ("1 番：门前清，有两组完全相同的顺子。", "1 番：門前清，有兩組完全相同的順子。", "1 han: closed, two identical runs.", "1 翻：門前で、同じ順子が 2 つ。", "1판: 멘젠, 똑같은 슌쯔 두 개."),
    "sanshoku": ("2 番：三门序数牌中点数相同的顺子（吃、碰后 1 番）。", "2 番：三門序數牌中點數相同的順子（吃、碰後 1 番）。", "2 han: the same run in all three suits (1 open).", "2 翻：3 種の数牌で同じ数の順子（鳴き 1 翻）。", "2판: 세 수패에서 같은 숫자의 슌쯔 (울면 1판)."),
    "ittsu": ("2 番：同一门序数牌的 123、456、789（吃、碰后 1 番）。", "2 番：同一門序數牌的 123、456、789（吃、碰後 1 番）。", "2 han: 123, 456 and 789 of one suit (1 open).", "2 翻：同じ数牌の 123・456・789（鳴き 1 翻）。", "2판: 같은 수패의 123·456·789 (울면 1판)."),
    "toitoi": ("2 番：四组都是刻子或杠子。", "2 番：四組都是刻子或槓子。", "2 han: every set a pung or kong.", "2 翻：4 面子すべてが刻子か槓子。", "2판: 몸통 넷이 모두 커쯔나 깡쯔."),
    "honitsu": ("3 番：只有一门序数牌和字牌（吃、碰后 2 番）。", "3 番：只有一門序數牌和字牌（吃、碰後 2 番）。", "3 han: one suit and honours only (2 open).", "3 翻：1 種の数牌と字牌だけ（鳴き 2 翻）。", "3판: 수패 한 종류와 자패만 (울면 2판)."),
}.items():
    k(f"{G}.hand.{key}", *texts)

k(f"{G}.riichi", "立直", "立直", "Riichi", "リーチ", "리치")
k(f"{G}.riichi.1", "门前清并且听牌时，可以宣告立直：按下「立直」，再选择要打出的牌，这张牌会横放。", "門前清並且聽牌時，可以宣告立直：按下「立直」，再選擇要打出的牌，這張牌會橫放。",
  "With a closed hand that is ready, you may declare riichi: press Riichi, then pick the tile to discard; it lies sideways.",
  "門前で聴牌しているとき、リーチを宣言できます。「リーチ」を押してから捨てる牌を選ぶと、その牌が横向きになります。",
  "멘젠으로 텐파이일 때 리치를 선언할 수 있습니다. 「리치」를 누른 뒤 버릴 패를 고르면 그 패가 옆으로 놓입니다.")
k(f"{G}.riichi.2", "立直需要至少 1000 点，并剩余至少 4 张可摸的牌。宣告的牌无人荣和时，放 1000 点作为供托。", "立直需要至少 1000 點，並剩餘至少 4 張可摸的牌。宣告的牌無人榮和時，放 1000 點作為供託。",
  "Riichi needs 1000 points and at least four tiles left to draw. Once nobody wins on the declaring tile, you put 1000 points on the table as a stick.",
  "リーチには持ち点 1000 点以上と、残り 4 枚以上が必要です。宣言牌でロンされなければ、1000 点を供託として出します。",
  "리치하려면 1000점 이상과 남은 패 4장 이상이 필요합니다. 선언패로 론당하지 않으면 1000점을 공탁으로 냅니다.")
k(f"{G}.riichi.3", "立直后手牌不能再改变，每次都打出摸到的牌，除非可以和牌，或者能做不改变听牌的暗杠。", "立直後手牌不能再改變，每次都打出摸到的牌，除非可以和牌，或者能做不改變聽牌的暗槓。",
  "After riichi your hand is fixed: you discard each tile you draw, unless you win or make a closed kong that leaves your waits as they were.",
  "リーチ後は手を変えられません。和了するか、待ちが変わらない暗槓をする場合を除き、引いた牌をそのまま捨てます。",
  "리치 후에는 패를 바꿀 수 없습니다. 화료하거나 대기가 바뀌지 않는 암깡을 하는 경우를 빼고 뽑은 패를 그대로 버립니다.")
k(f"{G}.riichi.4", "立直后一巡内和牌，且期间无人吃、碰、杠，另计一发。", "立直後一巡內和牌，且期間無人吃、碰、槓，另計一發。",
  "Winning within one go round after riichi, with no claims in between, adds ippatsu.", "リーチ後 1 巡以内に、その間に鳴きがなく和了すると一発が付きます。", "리치 후 한 바퀴 안에, 그 사이 울기 없이 화료하면 일발이 붙습니다.")
k(f"{G}.riichi.5", "立直和牌时，翻开宝牌指示牌下面的牌作为里宝牌指示牌。", "立直和牌時，翻開寶牌指示牌下面的牌作為裏寶牌指示牌。",
  "When a riichi hand wins, the tiles under the dora indicators are turned up as ura dora indicators.", "リーチで和了すると、ドラ表示牌の下の牌を裏ドラ表示牌としてめくります。", "리치로 화료하면 도라 표시패 아래의 패를 뒷도라 표시패로 뒤집습니다.")

k(f"{G}.furiten", "振听", "振聽", "Furiten", "フリテン", "후리텐")
k(f"{G}.furiten.1", "以下情况称为振听。振听时不能荣和，但仍可以自摸和牌：", "以下情況稱為振聽。振聽時不能榮和，但仍可以自摸和牌：",
  "In these cases you are furiten: you may not win on a discard, though you may still win by tsumo.", "次の場合はフリテンです。ロンはできませんが、ツモ和了はできます。", "다음 경우는 후리텐입니다. 론은 할 수 없지만 쯔모 화료는 할 수 있습니다.")
k(f"{G}.furiten.2", "你听的牌中，有一张你自己曾经打出过。", "你聽的牌中，有一張你自己曾經打出過。", "You discarded one of the tiles you wait on.", "待ち牌のどれかを自分で捨てている。", "대기패 중 하나를 스스로 버렸다.")
k(f"{G}.furiten.3", "有人打出你能荣和的牌而你没有和：到你下一次打牌为止。", "有人打出你能榮和的牌而你沒有和：到你下一次打牌為止。",
  "You let a tile you could win on go by: until your next discard.", "ロンできる牌を見逃した：次に自分が牌を捨てるまで。", "론할 수 있는 패를 놓쳤다: 다음에 내가 패를 버릴 때까지.")
k(f"{G}.furiten.4", "立直后让能荣和的牌过去：直到本局结束。", "立直後讓能榮和的牌過去：直到本局結束。", "You let one go after riichi: for the rest of the hand.", "リーチ後に見逃した：その局の終わりまで。", "리치 후에 놓쳤다: 그 국이 끝날 때까지.")

k(f"{G}.dora", "宝牌", "寶牌", "Dora", "ドラ", "도라")
k(f"{G}.dora.1", "王牌上翻开的牌是宝牌指示牌，它的下一张牌就是宝牌。和牌时，手中每有一张宝牌加 1 番。",
  "王牌上翻開的牌是寶牌指示牌，它的下一張牌就是寶牌。和牌時，手中每有一張寶牌加 1 番。",
  "The tile turned up on the dead wall is the dora indicator; the tile after it is dora. A winning hand counts 1 han for each dora in it.",
  "王牌でめくられた牌がドラ表示牌で、その次の牌がドラです。和了した手にドラが 1 枚あるごとに 1 翻加わります。",
  "왕패에서 뒤집힌 패가 도라 표시패이고, 그다음 패가 도라입니다. 화료한 패에 도라가 한 장 있을 때마다 1판이 더해집니다.")
k(f"{G}.dora.example", "宝牌指示牌为 5 筒时，6 筒是宝牌。", "寶牌指示牌為 5 筒時，6 筒是寶牌。", "With 5 of dots turned up, 6 of dots is dora.", "表示牌が 5 筒なら 6 筒がドラです。", "표시패가 5통이면 6통이 도라입니다.")
k(f"{G}.dora.2", "9 的下一张是 1；风牌按东、南、西、北循环；箭牌按白、发、中循环。", "9 的下一張是 1；風牌按東、南、西、北循環；箭牌按白、發、中循環。",
  "After 9 comes 1; winds go east, south, west, north; dragons go white, green, red, and round again.",
  "9 の次は 1、風牌は東南西北、三元牌は白發中の順に巡ります。", "9 다음은 1이고, 풍패는 동남서북, 삼원패는 백발중 순서로 돕니다.")
k(f"{G}.dora.3", "每次杠牌后，再翻开一张宝牌指示牌。", "每次槓牌後，再翻開一張寶牌指示牌。", "Each kong turns up another dora indicator.", "カンするたびに、ドラ表示牌がもう 1 枚めくられます。", "깡할 때마다 도라 표시패가 한 장 더 뒤집힙니다.")
k(f"{G}.dora.4", "宝牌、赤宝牌和里宝牌只增加番数，不能代替役。", "寶牌、赤寶牌和裏寶牌只增加番數，不能代替役。", "Dora, red fives and ura dora only add han; they cannot stand in for a yaku.", "ドラ・赤ドラ・裏ドラは翻を増やすだけで、役の代わりにはなりません。", "도라·적도라·뒷도라는 판수만 늘리며 역을 대신할 수 없습니다.")

k(f"{G}.list", "役一览", "役一覽", "Every yaku", "役一覧", "역 일람")
k(f"{G}.list.intro", "全部役按番数列出如下。和牌后，结算画面会列出这手牌计入的役与番数。", "全部役按番數列出如下。和牌後，結算畫面會列出這手牌計入的役與番數。",
  "Every yaku, by han. After a win, the result lists the yaku the hand counted.", "すべての役を翻数ごとに挙げます。和了すると、結果画面に数えた役と翻数が表示されます。", "모든 역을 판수별로 나열합니다. 화료하면 결과 화면에 센 역과 판수가 표시됩니다.")
k(f"{G}.list.open", "混全带幺九、一气通贯、三色同顺、纯全带幺九、混一色、清一色在吃、碰后减 1 番；立直、一发、门前清自摸和、平和、一杯口、二杯口只能在门前清时计算。",
  "混全帶么九、一氣通貫、三色同順、純全帶么九、混一色、清一色在吃、碰後減 1 番；立直、一發、門前清自摸和、平和、一盃口、二盃口只能在門前清時計算。",
  "Half outside hand, pure straight, mixed triple sequence, fully outside hand, half flush and full flush count one han fewer when open; riichi, ippatsu, fully concealed tsumo, pinfu and the double sequences need a closed hand.",
  "混全帯幺九・一気通貫・三色同順・純全帯幺九・混一色・清一色は鳴くと 1 翻下がります。立直・一発・門前清自摸和・平和・一盃口・二盃口は門前のときだけ成立します。",
  "찬타·일기통관·삼색동순·준찬타·혼일색·청일색은 울면 1판 내려갑니다. 리치·일발·멘젠쯔모·핑후·이페코·량페코는 멘젠일 때만 성립합니다.")

k(f"{G}.points", "计分", "計分", "Points", "得点", "점수")
k(f"{G}.points.1", "和牌的点数由番数和符数决定。符数按这手牌的组成计算：刻子、杠子、役牌雀头和听牌方式都会增加符数。",
  "和牌的點數由番數和符數決定。符數按這手牌的組成計算：刻子、槓子、役牌雀頭和聽牌方式都會增加符數。",
  "A win's points come from its han and fu. Fu count how the hand is made: pungs, kongs, a value pair and the wait add to them.",
  "得点は翻と符で決まります。符は手の形で数え、刻子・槓子・役牌の雀頭・待ちの形で増えます。",
  "점수는 판과 부로 정해집니다. 부는 패의 형태로 세며, 커쯔·깡쯔·역패 머리·대기 형태에 따라 늘어납니다.")
k(f"{G}.points.2", "结算时，画面会显示番数、符数和点数，不需要自己计算。", "結算時，畫面會顯示番數、符數和點數，不需要自己計算。",
  "The result shows the han, fu and points; you need not work them out yourself.", "結果画面に翻・符・点数が表示されるので、自分で計算する必要はありません。", "결과 화면에 판·부·점수가 표시되므로 직접 계산할 필요는 없습니다.")
k(f"{G}.points.3", "满贯：5 番，或番数与符数合计达到满贯；子家 8000 点，庄家 12000 点。", "滿貫：5 番，或番數與符數合計達到滿貫；子家 8000 點，莊家 12000 點。",
  "Mangan: 5 han, or enough han and fu to reach it; 8000 points, or 12000 for the dealer.", "満貫：5 翻、または翻と符で満貫に達したとき。子 8000 点、親 12000 点。", "만관: 5판, 또는 판과 부로 만관에 이르렀을 때. 자 8000점, 선 12000점.")
k(f"{G}.points.4", "跳满 6 至 7 番，倍满 8 至 10 番，三倍满 11 至 12 番，13 番以上为累计役满。役满为子家 32000 点，庄家 48000 点。",
  "跳滿 6 至 7 番，倍滿 8 至 10 番，三倍滿 11 至 12 番，13 番以上為累計役滿。役滿為子家 32000 點，莊家 48000 點。",
  "Haneman is 6–7 han, baiman 8–10, sanbaiman 11–12, and 13 or more a counted yakuman. A yakuman is 32000 points, or 48000 for the dealer.",
  "跳満 6〜7 翻、倍満 8〜10 翻、三倍満 11〜12 翻、13 翻以上は数え役満です。役満は子 32000 点、親 48000 点です。",
  "하네만 6~7판, 배만 8~10판, 삼배만 11~12판, 13판 이상은 헤아림 역만입니다. 역만은 자 32000점, 선 48000점입니다.")
k(f"{G}.points.5", "荣和：打出这张牌的玩家支付全部点数。自摸：庄家和牌时其余三人平分；子家和牌时庄家支付一半，另外两人各支付四分之一。",
  "榮和：打出這張牌的玩家支付全部點數。自摸：莊家和牌時其餘三人平分；子家和牌時莊家支付一半，另外兩人各支付四分之一。",
  "Ron: the discarder pays it all. Tsumo: when the dealer wins, the other three pay equal shares; otherwise the dealer pays half and the other two a quarter each.",
  "ロン：放銃した人が全額払います。ツモ：親の和了なら 3 人が等分し、子の和了なら親が半分、残り 2 人が 4 分の 1 ずつ払います。",
  "론: 쏘인 사람이 전부 냅니다. 쯔모: 선이 화료하면 세 사람이 똑같이 나누고, 자가 화료하면 선이 절반, 나머지 두 사람이 4분의 1씩 냅니다.")
k(f"{G}.points.6", "每个本场在荣和时加 300 点，自摸时每人多付 100 点。", "每個本場在榮和時加 300 點，自摸時每人多付 100 點。",
  "Each honba adds 300 points to a ron, or 100 from each player to a tsumo.", "本場 1 本につき、ロンなら 300 点、ツモなら各自 100 点が加わります。", "본장 하나마다 론이면 300점, 쯔모면 각자 100점이 더해집니다.")
k(f"{G}.points.7", "桌上的供托全部归和牌者。", "桌上的供託全部歸和牌者。", "The riichi sticks on the table go to the winner.", "卓上の供託はすべて和了した人のものになります。", "테이블 위의 공탁은 모두 화료한 사람이 가져갑니다.")

k(f"{G}.game", "一场的进行", "一場的進行", "Hands and rounds", "局の進み方", "국의 진행")
k(f"{G}.game.1", "庄家为东，其余玩家依次为南、西、北。东场的场风是东，南场的场风是南。", "莊家為東，其餘玩家依次為南、西、北。東場的場風是東，南場的場風是南。",
  "The dealer is east and the others south, west and north. In the east round the round's wind is east; in the south round, south.",
  "親が東、ほかは順に南・西・北です。東場の場風は東、南場は南です。", "선이 동이고 나머지는 차례로 남·서·북입니다. 동장의 장풍은 동, 남장은 남입니다.")
k(f"{G}.game.2", "庄家和牌，或流局时庄家听牌，庄家连庄，本场数加一。", "莊家和牌，或流局時莊家聽牌，莊家連莊，本場數加一。",
  "When the dealer wins, or is ready at a draw, they keep the deal, with one more honba.", "親が和了するか、流局時に聴牌していれば連荘し、本場が 1 つ増えます。", "선이 화료하거나 유국 때 텐파이면 연장하며 본장이 하나 늘어납니다.")
k(f"{G}.game.3", "子家和牌时，庄家轮给下家，本场数归零；流局时庄家未听牌，庄家也轮给下家，但本场数加一。",
  "子家和牌時，莊家輪給下家，本場數歸零；流局時莊家未聽牌，莊家也輪給下家，但本場數加一。",
  "When another player wins, the deal passes on and honba go back to none; at a draw with the dealer not ready, it passes on with one more honba.",
  "子が和了すると親が下家に移り、本場は 0 に戻ります。流局で親がノーテンなら親は移りますが、本場は 1 つ増えます。",
  "자가 화료하면 선이 하가로 넘어가고 본장은 0이 됩니다. 유국 때 선이 노텐이면 선은 넘어가지만 본장은 하나 늘어납니다.")
k(f"{G}.game.4", "最后一局结束后一场结束。最后一局庄家连庄且点数第一时，一场也随即结束。", "最後一局結束後一場結束。最後一局莊家連莊且點數第一時，一場也隨即結束。",
  "The game ends after the last hand, or when the dealer of the last hand keeps the deal while on top.", "最後の局が終わると終了です。最後の局で親が連荘し、かつトップのときもそこで終了します。", "마지막 국이 끝나면 끝납니다. 마지막 국에서 선이 연장하면서 1등이면 그때도 끝납니다.")
k(f"{G}.game.5", "结束时，桌上剩余的供托归点数第一的玩家。", "結束時，桌上剩餘的供託歸點數第一的玩家。", "Riichi sticks left on the table at the end go to whoever is on top.", "終了時に残っている供託はトップの人のものになります。", "끝날 때 남은 공탁은 1등이 가져갑니다.")

k(f"{G}.draws", "流局", "流局", "Draws", "流局", "유국")
k(f"{G}.draws.1", "可摸的牌摸完而无人和牌时为流局。未听牌的玩家共支付 3000 点，由听牌的玩家平分。",
  "可摸的牌摸完而無人和牌時為流局。未聽牌的玩家共支付 3000 點，由聽牌的玩家平分。",
  "When the tiles to draw run out and nobody has won, the hand is a draw. The players not ready pay 3000 points between them to those who are.",
  "引ける牌が尽きて誰も和了しなければ流局です。ノーテンの人が合わせて 3000 点を払い、テンパイの人で分けます。",
  "뽑을 패가 다 떨어지고 아무도 화료하지 못하면 유국입니다. 노텐인 사람들이 합쳐서 3000점을 내고 텐파이인 사람들이 나눠 갖습니다.")
k(f"{G}.draws.2", "若有人打出的牌全部是幺九牌且无人吃、碰，则计流局满贯，按满贯自摸收分。", "若有人打出的牌全部是么九牌且無人吃、碰，則計流局滿貫，按滿貫自摸收分。",
  "A player whose discards were all terminals and honours, none of them claimed, scores a mangan at the draw, paid as a tsumo.",
  "捨て牌がすべて幺九牌で、どれも鳴かれていなければ流し満貫となり、満貫のツモとして受け取ります。",
  "버린 패가 모두 요구패이고 하나도 울리지 않았다면 유국만관이 되어 만관 쯔모로 받습니다.")
k(f"{G}.draws.3", "九种九牌：第一次摸牌时、此前无人吃碰杠，手中有九种以上幺九牌，可以选择流局。", "九種九牌：第一次摸牌時、此前無人吃碰槓，手中有九種以上么九牌，可以選擇流局。",
  "Nine terminals: on your first draw, with nobody having claimed, nine or more kinds of terminals and honours let you call the hand off.",
  "九種九牌：最初のツモで、それまで鳴きがなく、幺九牌が 9 種以上あれば流局にできます。", "구종구패: 첫 쯔모 때, 그때까지 울기가 없고 요구패가 9종 이상이면 유국으로 할 수 있습니다.")
k(f"{G}.draws.4", "四风连打：第一巡四人都打出同一种风牌。", "四風連打：第一巡四人都打出同一種風牌。", "Four winds: all four players discard the same wind in the first go round.", "四風連打：1 巡目に 4 人全員が同じ風牌を捨てる。", "사풍연타: 첫 바퀴에 네 명 모두 같은 풍패를 버림.")
k(f"{G}.draws.5", "四家立直：四人都宣告了立直。", "四家立直：四人都宣告了立直。", "Four riichi: all four players declare riichi.", "四家立直：4 人全員がリーチする。", "사가리치: 네 명 모두 리치함.")
k(f"{G}.draws.6", "四杠散了：两人以上共杠了四次。中途流局时庄家连庄，本场数加一。", "四槓散了：兩人以上共槓了四次。中途流局時莊家連莊，本場數加一。",
  "Four kongs: four kongs made by more than one player. After any of these the dealer keeps the deal, with one more honba.",
  "四槓散了：2 人以上で合計 4 回カンする。途中流局では親が連荘し、本場が 1 つ増えます。", "사깡유국: 두 명 이상이 합쳐서 4번 깡함. 도중 유국에서는 선이 연장하고 본장이 하나 늘어납니다.")

k(f"{G}.help", "辅助提示", "輔助提示", "Help at the table", "補助表示", "보조 표시")
k(f"{G}.help.1", "牌桌会帮助你看清自己的手牌：", "牌桌會幫助你看清自己的手牌：", "The table helps you read your hand:", "卓では、自分の手を把握する手助けをします。", "테이블은 내 패를 파악하도록 돕습니다.")
k(f"{G}.help.2", "听牌后，所听的牌显示在你的名字旁边，每张牌旁的数字是尚未出现的张数，后面是荣和时的番数和符数。显示为灰色的牌无役，不能和牌。",
  "聽牌後，所聽的牌顯示在你的名字旁邊，每張牌旁的數字是尚未出現的張數，後面是榮和時的番數和符數。顯示為灰色的牌無役，不能和牌。",
  "Once you are ready, your waits show beside your name with how many are still unseen and what a ron would score. A greyed tile has no yaku and cannot win.",
  "聴牌すると、待ち牌が名前の横に表示されます。数字はまだ見えていない枚数で、続けてロン時の翻と符を示します。灰色の牌は役がなく和了できません。",
  "텐파이하면 대기패가 이름 옆에 표시됩니다. 숫자는 아직 보이지 않은 장수이고, 이어서 론했을 때의 판과 부를 보여 줍니다. 회색 패는 역이 없어 화료할 수 없습니다.")
k(f"{G}.help.3", "轮到你出牌时，打出后可以听牌的牌上方有标记：绿色表示可以和牌，黄色表示所听的牌都无役或振听。",
  "輪到你出牌時，打出後可以聽牌的牌上方有標記：綠色表示可以和牌，黃色表示所聽的牌都無役或振聽。",
  "On your turn, a mark over a tile says discarding it leaves you ready: green when you could win, yellow when every wait has no yaku or is furiten.",
  "自分の番では、捨てると聴牌になる牌の上に印が付きます。緑は和了できること、黄はどの待ちも役なしかフリテンであることを示します。",
  "내 차례에는 버리면 텐파이가 되는 패 위에 표시가 붙습니다. 초록은 화료할 수 있다는 뜻이고, 노랑은 어느 대기패도 역이 없거나 후리텐이라는 뜻입니다.")
k(f"{G}.help.4", "选中一张牌或将指针移到牌上，可以看到打出它之后所听的牌。", "選中一張牌或將指標移到牌上，可以看到打出它之後所聽的牌。",
  "Pick a tile, or point at it, to see what you would wait on after discarding it.", "牌を選ぶかカーソルを合わせると、それを捨てた後の待ちが表示されます。", "패를 고르거나 커서를 올리면, 그 패를 버린 뒤의 대기패가 표시됩니다.")
k(f"{G}.help.5", "振听时，宝牌指示牌下方会显示「振听」。", "振聽時，寶牌指示牌下方會顯示「振聽」。", "When you are furiten, it says so under the dora indicators.", "フリテンのときは、ドラ表示牌の下に「フリテン」と表示されます。", "후리텐일 때는 도라 표시패 아래에 「후리텐」이 표시됩니다.")
k(f"{G}.help.6", "可以和牌时，按钮上会显示这手牌的番数和符数，或达到的满贯等级。", "可以和牌時，按鈕上會顯示這手牌的番數和符數，或達到的滿貫等級。",
  "When you can win, the button shows the han and fu, or the limit the hand reaches.", "和了できるときは、ボタンに翻と符、または満貫などの段階が表示されます。", "화료할 수 있을 때는 버튼에 판과 부, 또는 만관 같은 단계가 표시됩니다.")

for group, keys in GROUPS.items():
    S[f"{G}.list.{group}"] = tuple(HEADS[group][i] + SEP[i].join(YAKU[y][i] for y in keys) + END[i] for i in range(5))

if __name__ == "__main__":
    update(S, {})
