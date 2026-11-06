"""Adds the Guobiao mahjong strings to every language file.

Run from the repository root: python3 tools/lang_mahjong_gb.py
Each entry is (zh_cn, zh_tw, en_us, ja_jp, ko_kr); zh_hk uses the zh_tw text unless
HK below says otherwise. Keys already present are replaced, new ones go after the last
mahjong key.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from lang_tools import update  # noqa: E402

FANS = {
    "big_four_winds": ("大四喜", "大四喜", "Big Four Winds", "大四喜", "대사희"),
    "big_three_dragons": ("大三元", "大三元", "Big Three Dragons", "大三元", "대삼원"),
    "all_green": ("绿一色", "綠一色", "All Green", "緑一色", "녹일색"),
    "nine_gates": ("九莲宝灯", "九蓮寶燈", "Nine Gates", "九蓮宝燈", "구련보등"),
    "four_kongs": ("四杠", "四槓", "Four Kongs", "四槓", "사깡"),
    "seven_shifted_pairs": ("连七对", "連七對", "Seven Shifted Pairs", "連七対", "연칠대"),
    "thirteen_orphans": ("十三幺", "十三么", "Thirteen Orphans", "十三幺", "십삼요"),
    "all_terminals": ("清幺九", "清么九", "All Terminals", "清幺九", "청요구"),
    "little_four_winds": ("小四喜", "小四喜", "Little Four Winds", "小四喜", "소사희"),
    "little_three_dragons": ("小三元", "小三元", "Little Three Dragons", "小三元", "소삼원"),
    "all_honours": ("字一色", "字一色", "All Honours", "字一色", "자일색"),
    "four_concealed_pungs": ("四暗刻", "四暗刻", "Four Concealed Pungs", "四暗刻", "사암각"),
    "pure_terminal_chows": ("一色双龙会", "一色雙龍會", "Pure Terminal Chows", "一色双龍会", "일색쌍룡회"),
    "quadruple_chow": ("一色四同顺", "一色四同順", "Quadruple Chow", "一色四同順", "일색사동순"),
    "four_pure_shifted_pungs": ("一色四节高", "一色四節高", "Four Pure Shifted Pungs", "一色四節高", "일색사절고"),
    "four_pure_shifted_chows": ("一色四步高", "一色四步高", "Four Pure Shifted Chows", "一色四歩高", "일색사보고"),
    "three_kongs": ("三杠", "三槓", "Three Kongs", "三槓", "삼깡"),
    "all_terminals_and_honours": ("混幺九", "混么九", "All Terminals and Honours", "混幺九", "혼요구"),
    "seven_pairs": ("七对", "七對", "Seven Pairs", "七対", "칠대"),
    "greater_honours_knitted": ("七星不靠", "七星不靠", "Greater Honours and Knitted Tiles", "七星不靠", "칠성불고"),
    "all_even_pungs": ("全双刻", "全雙刻", "All Even Pungs", "全双刻", "전쌍각"),
    "full_flush": ("清一色", "清一色", "Full Flush", "清一色", "청일색"),
    "pure_triple_chow": ("一色三同顺", "一色三同順", "Pure Triple Chow", "一色三同順", "일색삼동순"),
    "pure_shifted_pungs": ("一色三节高", "一色三節高", "Pure Shifted Pungs", "一色三節高", "일색삼절고"),
    "upper_tiles": ("全大", "全大", "Upper Tiles", "全大", "전대"),
    "middle_tiles": ("全中", "全中", "Middle Tiles", "全中", "전중"),
    "lower_tiles": ("全小", "全小", "Lower Tiles", "全小", "전소"),
    "pure_straight": ("清龙", "清龍", "Pure Straight", "清龍", "청룡"),
    "three_suited_terminal_chows": ("三色双龙会", "三色雙龍會", "Three-Suited Terminal Chows", "三色双龍会", "삼색쌍룡회"),
    "pure_shifted_chows": ("一色三步高", "一色三步高", "Pure Shifted Chows", "一色三歩高", "일색삼보고"),
    "all_fives": ("全带五", "全帶五", "All Fives", "全帯五", "전대오"),
    "triple_pung": ("三同刻", "三同刻", "Triple Pung", "三同刻", "삼동각"),
    "three_concealed_pungs": ("三暗刻", "三暗刻", "Three Concealed Pungs", "三暗刻", "삼암각"),
    "lesser_honours_knitted": ("全不靠", "全不靠", "Lesser Honours and Knitted Tiles", "全不靠", "전불고"),
    "knitted_straight": ("组合龙", "組合龍", "Knitted Straight", "組合龍", "조합룡"),
    "upper_four": ("大于五", "大於五", "Upper Four", "大於五", "대어오"),
    "lower_four": ("小于五", "小於五", "Lower Four", "小於五", "소어오"),
    "big_three_winds": ("三风刻", "三風刻", "Big Three Winds", "三風刻", "삼풍각"),
    "mixed_straight": ("花龙", "花龍", "Mixed Straight", "花龍", "화룡"),
    "reversible_tiles": ("推不倒", "推不倒", "Reversible Tiles", "推不倒", "추부도"),
    "mixed_triple_chow": ("三色三同顺", "三色三同順", "Mixed Triple Chow", "三色三同順", "삼색삼동순"),
    "mixed_shifted_pungs": ("三色三节高", "三色三節高", "Mixed Shifted Pungs", "三色三節高", "삼색삼절고"),
    "chicken_hand": ("无番和", "無番和", "Chicken Hand", "無番和", "무번화"),
    "last_tile_draw": ("妙手回春", "妙手回春", "Last Tile Draw", "妙手回春", "묘수회춘"),
    "last_tile_claim": ("海底捞月", "海底撈月", "Last Tile Claim", "海底撈月", "해저로월"),
    "out_with_replacement": ("杠上开花", "槓上開花", "Out with Replacement Tile", "槓上開花", "깡상개화"),
    "robbing_the_kong": ("抢杠和", "搶槓和", "Robbing the Kong", "搶槓和", "창깡화"),
    "all_pungs": ("碰碰和", "碰碰和", "All Pungs", "碰碰和", "퐁퐁화"),
    "half_flush": ("混一色", "混一色", "Half Flush", "混一色", "혼일색"),
    "mixed_shifted_chows": ("三色三步高", "三色三步高", "Mixed Shifted Chows", "三色三歩高", "삼색삼보고"),
    "all_types": ("五门齐", "五門齊", "All Types", "五門斉", "오문제"),
    "melded_hand": ("全求人", "全求人", "Melded Hand", "全求人", "전구인"),
    "two_concealed_kongs": ("双暗杠", "雙暗槓", "Two Concealed Kongs", "双暗槓", "쌍암깡"),
    "two_dragon_pungs": ("双箭刻", "雙箭刻", "Two Dragon Pungs", "双箭刻", "쌍전각"),
    "outside_hand": ("全带幺", "全帶么", "Outside Hand", "全帯幺", "전대요"),
    "fully_concealed": ("不求人", "不求人", "Fully Concealed Hand", "不求人", "불구인"),
    "two_melded_kongs": ("双明杠", "雙明槓", "Two Melded Kongs", "双明槓", "쌍명깡"),
    "last_of_kind": ("和绝张", "和絕張", "Last Tile", "和絶張", "화절장"),
    "dragon_pung": ("箭刻", "箭刻", "Dragon Pung", "箭刻", "전각"),
    "prevalent_wind": ("圈风刻", "圈風刻", "Prevalent Wind", "圏風刻", "권풍각"),
    "seat_wind": ("门风刻", "門風刻", "Seat Wind", "門風刻", "문풍각"),
    "concealed_hand": ("门前清", "門前清", "Concealed Hand", "門前清", "문전청"),
    "all_chows": ("平和", "平和", "All Chows", "平和", "평화"),
    "tile_hog": ("四归一", "四歸一", "Tile Hog", "四帰一", "사귀일"),
    "double_pung": ("双同刻", "雙同刻", "Double Pung", "双同刻", "쌍동각"),
    "two_concealed_pungs": ("双暗刻", "雙暗刻", "Two Concealed Pungs", "双暗刻", "쌍암각"),
    "concealed_kong": ("暗杠", "暗槓", "Concealed Kong", "暗槓", "암깡"),
    "all_simples": ("断幺", "斷么", "All Simples", "断幺", "단요"),
    "pure_double_chow": ("一般高", "一般高", "Pure Double Chow", "一般高", "일반고"),
    "mixed_double_chow": ("喜相逢", "喜相逢", "Mixed Double Chow", "喜相逢", "희상봉"),
    "short_straight": ("连六", "連六", "Short Straight", "連六", "연륙"),
    "two_terminal_chows": ("老少副", "老少副", "Two Terminal Chows", "老少副", "노소부"),
    "pung_of_terminals_or_honours": ("幺九刻", "么九刻", "Pung of Terminals or Honours", "幺九刻", "요구각"),
    "melded_kong": ("明杠", "明槓", "Melded Kong", "明槓", "명깡"),
    "one_voided_suit": ("缺一门", "缺一門", "One Voided Suit", "缺一門", "결일문"),
    "no_honours": ("无字", "無字", "No Honours", "無字", "무자"),
    "edge_wait": ("边张", "邊張", "Edge Wait", "辺張", "변장"),
    "closed_wait": ("坎张", "嵌張", "Closed Wait", "嵌張", "감장"),
    "single_wait": ("单钓将", "單釣將", "Single Wait", "単釣将", "단조장"),
    "self_drawn": ("自摸", "自摸", "Self-Drawn", "自摸", "자모"),
    "flower_tiles": ("花牌", "花牌", "Flower Tiles", "花牌", "화패"),
}

VALUES = {
    88: ["big_four_winds", "big_three_dragons", "all_green", "nine_gates", "four_kongs", "seven_shifted_pairs", "thirteen_orphans"],
    64: ["all_terminals", "little_four_winds", "little_three_dragons", "all_honours", "four_concealed_pungs", "pure_terminal_chows"],
    48: ["quadruple_chow", "four_pure_shifted_pungs"],
    32: ["four_pure_shifted_chows", "three_kongs", "all_terminals_and_honours"],
    24: ["seven_pairs", "greater_honours_knitted", "all_even_pungs", "full_flush", "pure_triple_chow", "pure_shifted_pungs", "upper_tiles", "middle_tiles", "lower_tiles"],
    16: ["pure_straight", "three_suited_terminal_chows", "pure_shifted_chows", "all_fives", "triple_pung", "three_concealed_pungs"],
    12: ["lesser_honours_knitted", "knitted_straight", "upper_four", "lower_four", "big_three_winds"],
    8: ["mixed_straight", "reversible_tiles", "mixed_triple_chow", "mixed_shifted_pungs", "chicken_hand", "last_tile_draw", "last_tile_claim", "out_with_replacement", "robbing_the_kong"],
    6: ["all_pungs", "half_flush", "mixed_shifted_chows", "all_types", "melded_hand", "two_concealed_kongs", "two_dragon_pungs"],
    4: ["outside_hand", "fully_concealed", "two_melded_kongs", "last_of_kind"],
    2: ["dragon_pung", "prevalent_wind", "seat_wind", "concealed_hand", "all_chows", "tile_hog", "double_pung", "two_concealed_pungs", "concealed_kong", "all_simples"],
    1: ["pure_double_chow", "mixed_double_chow", "short_straight", "two_terminal_chows", "pung_of_terminals_or_honours", "melded_kong", "one_voided_suit", "no_honours", "edge_wait", "closed_wait", "single_wait", "self_drawn", "flower_tiles"],
}

LANGS = ["zh_cn", "zh_tw", "en_us", "ja_jp", "ko_kr"]
LIST_FORMAT = {
    "zh_cn": ("%d\u00a0番：", "、", "。"),
    "zh_tw": ("%d\u00a0番：", "、", "。"),
    "en_us": ("%d fan: ", ", ", "."),
    "ja_jp": ("%d\u00a0番：", "、", "。"),
    "ko_kr": ("%d번: ", ", ", "."),
}

S = {}


def k(key, zh_cn, zh_tw, en_us, ja_jp, ko_kr):
    S[key] = (zh_cn, zh_tw, en_us, ja_jp, ko_kr)


# ---- Tiles and words every mahjong rule set shares ----------------------------------------------
for i, (cn, tw, en, ja, ko) in enumerate([("东风", "東風", "East Wind", "東", "동"), ("南风", "南風", "South Wind", "南", "남"),
                                          ("西风", "西風", "West Wind", "西", "서"), ("北风", "北風", "North Wind", "北", "북")], 1):
    k(f"tukuai.mahjong.tile.wind.{i}", cn, tw, en, ja, ko)
for i, (cn, tw, en, ja, ko) in enumerate([("红中", "紅中", "Red Dragon", "中", "중"), ("发财", "發財", "Green Dragon", "發", "발"),
                                          ("白板", "白板", "White Dragon", "白", "백")], 1):
    k(f"tukuai.mahjong.tile.dragon.{i}", cn, tw, en, ja, ko)
for i, (cn, tw, en, ja, ko) in enumerate([("春", "春", "Spring", "春", "춘"), ("夏", "夏", "Summer", "夏", "하"), ("秋", "秋", "Autumn", "秋", "추"),
                                          ("冬", "冬", "Winter", "冬", "동"), ("梅", "梅", "Plum", "梅", "매"), ("兰", "蘭", "Orchid", "蘭", "란"),
                                          ("竹", "竹", "Bamboo", "竹", "죽"), ("菊", "菊", "Chrysanthemum", "菊", "국")], 1):
    k(f"tukuai.mahjong.tile.flower.{i}", cn, tw, en, ja, ko)
k("tukuai.mahjong.tile.red", "赤%s", "赤%s", "Red %s", "赤%s", "적%s")
for i, (cn, tw, en, ja, ko) in enumerate([("东", "東", "East", "東", "동"), ("南", "南", "South", "南", "남"),
                                          ("西", "西", "West", "西", "서"), ("北", "北", "North", "北", "북")]):
    k(f"tukuai.mahjong.wind.{i}", cn, tw, en, ja, ko)
k("tukuai.mahjong.run", "%s%s%s%s", "%s%s%s%s", "%s-%s-%s %s", "%s%s%s%s", "%s%s%s%s")
k("tukuai.mahjong.chow_run", "吃 %s", "吃 %s", "Chow %s", "チー %s", "치 %s")
k("tukuai.mahjong.dealer_badge", "·庄", "·莊", " · Dealer", "・親", " · 선")
k("tukuai.mahjong.not_ready_after", "打出后不能听牌", "打出後不能聽牌", "Not ready after this discard", "捨てても聴牌しない", "버려도 텐파이가 아님")
k("tukuai.mahjong.left_total", "共剩 %s 张", "共剩 %s 張", "%s left", "残り %s 枚", "남은 패 %s장")
k("tukuai.mahjong.cannot_win", "所听的牌均不能和牌", "所聽的牌均不能和牌", "none of them would win", "どれも和了できない", "어느 패로도 화료할 수 없음")
k("tukuai.mahjong.next_hand", "下一局", "下一局", "Next hand", "次の局へ", "다음 국")

# ---- Guobiao: the game at the table --------------------------------------------------------------
k("tukuai.game.guobiao", "国标麻将", "國標麻將", "Guobiao Mahjong", "中国公式麻雀", "국표마작")
k("tukuai.preset.guobiao.east", "东风圈（四局）", "東風圈（四局）", "One round (4 hands)", "東風圏（4 局）", "동풍권 (4국)")
k("tukuai.preset.guobiao.full", "全庄（十六局）", "全莊（十六局）", "Full game (16 hands)", "全荘（16 局）", "전장 (16국)")
k("tukuai.preset.guobiao.no_flowers", "东风圈，不用花牌", "東風圈，不用花牌", "One round, no flowers", "東風圏・花牌なし", "동풍권, 화패 없음")
for key, names in FANS.items():
    cn, tw, en, ja, ko = names
    k(f"tukuai.guobiao.fan.{key}", cn, tw, en, ja, ko)
k("tukuai.guobiao.fan_count", "%s 番", "%s 番", "%s fan", "%s 番", "%s번")
k("tukuai.guobiao.win_with", "和牌（%s）", "和牌（%s）", "Win (%s)", "和了（%s）", "화료 (%s)")
k("tukuai.guobiao.note.can_win", "可以自摸和牌（%s），也可以打出一张牌继续", "可以自摸和牌（%s），也可以打出一張牌繼續",
  "You can win on this tile (%s), or discard to play on", "ツモ和了できます（%s）。牌を捨てて続けることもできます", "쯔모로 화료할 수 있습니다 (%s). 패를 버리고 계속할 수도 있습니다")
k("tukuai.guobiao.note.can_win_claim", "可以和这张牌（%s）", "可以和這張牌（%s）", "You can win on this tile (%s)", "この牌で和了できます（%s）", "이 패로 화료할 수 있습니다 (%s)")
k("tukuai.guobiao.round", "%s风圈第 %s 局（%s/%s）", "%s風圈第 %s 局（%s/%s）", "%s round, hand %s (%s of %s)", "%s風圏 第 %s 局（%s/%s）", "%s풍권 제%s국 (%s/%s)")
k("tukuai.guobiao.hand_title", "%s风圈第 %s 局", "%s風圈第 %s 局", "%s round, hand %s", "%s風圏 第 %s 局", "%s풍권 제%s국")
k("tukuai.guobiao.won", "%s 和牌", "%s 和牌", "%s won", "%s の和了", "%s 님 화료")
k("tukuai.guobiao.robbed_from", "抢 %s 的杠和牌", "搶 %s 的槓和牌", "Robbed %s's kong", "%s の加槓を搶槓", "%s 님의 가깡을 창깡")
k("tukuai.guobiao.dealt_in", "和 %s 打出的牌", "和 %s 打出的牌", "On %s's discard", "%s の捨て牌で和了", "%s 님이 버린 패로 화료")
k("tukuai.guobiao.exhausted", "牌墙已摸完，无人和牌，本局不计分。", "牌牆已摸完，無人和牌，本局不計分。",
  "The wall ran out and nobody won. The hand scores nothing.", "山が尽き、誰も和了しませんでした。この局は得点の移動がありません。", "패산이 다 떨어졌고 아무도 화료하지 못했습니다. 이 국은 점수가 없습니다.")
k("tukuai.guobiao.total", "合计 %s 番", "合計 %s 番", "%s fan in all", "合計 %s 番", "합계 %s번")
k("tukuai.guobiao.this_hand", "本局", "本局", "Hand", "この局", "이번 국")
k("tukuai.guobiao.in_all", "累计", "累計", "Total", "累計", "누계")

# ---- Guobiao: the rule book ---------------------------------------------------------------------
G = "tukuai.guide.guobiao"
k(f"{G}.table", "本桌规则", "本桌規則", "This table", "この卓のルール", "이 테이블의 규칙")
k(f"{G}.table.flowers.on", "使用八张花牌，共 144 张牌。", "使用八張花牌，共 144 張牌。", "Eight flowers are in the wall: 144 tiles in all.", "花牌 8 枚を使い、全部で 144 枚です。", "화패 8장을 사용하며, 모두 144장입니다.")
k(f"{G}.table.flowers.off", "不使用花牌，共 136 张牌。", "不使用花牌，共 136 張牌。", "No flowers: 136 tiles in all.", "花牌を使わず、全部で 136 枚です。", "화패를 사용하지 않으며, 모두 136장입니다.")
k(f"{G}.table.hands", "一场共 %s 局。", "一場共 %s 局。", "A game is %s hands.", "1 試合は %s 局です。", "한 판은 %s국입니다.")
k(f"{G}.table.full", "一场共十六局：东、南、西、北四圈，每圈四局。", "一場共十六局：東、南、西、北四圈，每圈四局。",
  "A game is 16 hands: four rounds, east, south, west and north, of four hands each.", "1 試合は 16 局です。東・南・西・北の 4 圏で、各圏 4 局です。", "한 판은 16국입니다. 동·남·서·북 4권이며, 권마다 4국입니다.")
k(f"{G}.table.min", "和牌至少需要 %s 番，花牌不计在内。", "和牌至少需要 %s 番，花牌不計在內。", "A winning hand needs at least %s fan, flowers not counted.", "和了には花牌を除いて %s 番以上が必要です。", "화료하려면 화패를 제외하고 %s번 이상이 필요합니다.")

k(f"{G}.goal", "玩法简介", "玩法簡介", "The game", "遊び方", "게임 소개")
k(f"{G}.goal.1", "四人国标麻将，按中国麻将竞赛规则进行。目标是用 14 张牌凑成一手和牌，并且这手牌至少值 8 番。",
  "四人國標麻將，按中國麻將競賽規則進行。目標是用 14 張牌湊成一手和牌，並且這手牌至少值 8 番。",
  "Four-player mahjong by the Chinese official rules for competition. The aim is to make a winning hand of 14 tiles that is worth at least 8 fan.",
  "中国の公式競技ルールによる 4 人麻雀です。14 枚で和了の形を作り、その手が 8 番以上になることを目指します。",
  "중국 공식 경기 규칙에 따른 4인 마작입니다. 14장으로 화료 형태를 만들고, 그 패가 8번 이상이 되도록 하는 것이 목표입니다.")
k(f"{G}.goal.2", "番种共 81 种，每一种代表一种牌型或和牌方式。番数越高，和牌时得分越多。",
  "番種共 81 種，每一種代表一種牌型或和牌方式。番數越高，和牌時得分越多。",
  "There are 81 kinds of fan, each a pattern in the hand or a way of winning. The more fan a hand is worth, the more it scores.",
  "番種は 81 種あり、それぞれが手の形か和了の仕方を表します。番数が多いほど、和了したときの得点が多くなります。",
  "번종은 모두 81가지이며, 각각 패의 형태나 화료 방식을 나타냅니다. 번수가 높을수록 화료할 때 점수가 많아집니다.")
k(f"{G}.goal.3", "一场由数局组成。每局在有人和牌或牌墙摸完时结束，分数在玩家之间转移，所有人的总分始终为零。",
  "一場由數局組成。每局在有人和牌或牌牆摸完時結束，分數在玩家之間轉移，所有人的總分始終為零。",
  "A game is several hands. Each ends when someone wins or the wall runs out. Points pass between the players, so they always add up to zero.",
  "1 試合は数局からなります。各局は誰かが和了するか山が尽きると終わります。得点はプレイヤーの間で移動し、合計は常にゼロです。",
  "한 판은 여러 국으로 이루어집니다. 각 국은 누군가 화료하거나 패산이 다 떨어지면 끝납니다. 점수는 플레이어 사이에서 오가므로 합계는 항상 0입니다.")

k(f"{G}.tiles", "牌", "牌", "Tiles", "牌", "패")
k(f"{G}.tiles.1", "万、筒、条三门序数牌各有 1 至 9，另有四种风牌和三种箭牌，每种各四张。此外还有八张花牌，每种一张。",
  "萬、筒、條三門序數牌各有 1 至 9，另有四種風牌和三種箭牌，每種各四張。此外還有八張花牌，每種一張。",
  "Three numbered suits, characters, dots and bamboo, run from 1 to 9; with four winds and three dragons there are four of each tile. There are also eight flowers, one of each.",
  "萬子・筒子・索子の 3 種の数牌がそれぞれ 1 から 9 まであり、風牌 4 種と三元牌 3 種があります。各牌 4 枚ずつです。ほかに花牌が 1 枚ずつ 8 枚あります。",
  "만·통·삭 세 가지 수패가 각각 1부터 9까지 있고, 풍패 4종과 삼원패 3종이 있습니다. 패마다 4장씩입니다. 그 밖에 화패가 한 장씩 8장 있습니다.")
k(f"{G}.tiles.characters", "万", "萬", "Characters", "萬子", "만수")
k(f"{G}.tiles.dots", "筒", "筒", "Dots", "筒子", "통수")
k(f"{G}.tiles.bamboo", "条", "條", "Bamboo", "索子", "삭수")
k(f"{G}.tiles.winds", "风牌", "風牌", "Winds", "風牌", "풍패")
k(f"{G}.tiles.winds.text", "东、南、西、北。风牌不能组成顺子。", "東、南、西、北。風牌不能組成順子。", "East, south, west and north. Winds make no runs.", "東・南・西・北。風牌は順子になりません。", "동·남·서·북. 풍패는 슌쯔를 만들 수 없습니다.")
k(f"{G}.tiles.dragons", "箭牌", "箭牌", "Dragons", "三元牌", "삼원패")
k(f"{G}.tiles.dragons.text", "中、发、白。箭牌也不能组成顺子。", "中、發、白。箭牌也不能組成順子。", "Red, green and white. Dragons make no runs either.", "中・發・白。三元牌も順子になりません。", "중·발·백. 삼원패도 슌쯔를 만들 수 없습니다.")
k(f"{G}.tiles.flowers", "花牌", "花牌", "Flowers", "花牌", "화패")
k(f"{G}.tiles.flowers.text", "春、夏、秋、冬、梅、兰、竹、菊。摸到花牌时，将其放在一旁，再从牌墙尾部补摸一张。每张花牌计 1 番，但不计入和牌所需的 8 番。",
  "春、夏、秋、冬、梅、蘭、竹、菊。摸到花牌時，將其放在一旁，再從牌牆尾部補摸一張。每張花牌計 1 番，但不計入和牌所需的 8 番。",
  "Spring, summer, autumn, winter, plum, orchid, bamboo and chrysanthemum. A player who draws one sets it aside and draws again from the back of the wall. Each is worth 1 fan, which does not count towards the 8 a hand needs.",
  "春・夏・秋・冬・梅・蘭・竹・菊。花牌を引いたら脇に置き、山の後ろから 1 枚補充します。花牌は 1 枚 1 番ですが、和了に必要な 8 番には数えません。",
  "춘·하·추·동·매·란·죽·국. 화패를 뽑으면 옆에 두고 패산 뒤쪽에서 한 장을 보충합니다. 화패는 장당 1번이지만, 화료에 필요한 8번에는 들어가지 않습니다.")

k(f"{G}.sets", "和牌的组成", "和牌的組成", "Winning hands", "和了の形", "화료의 형태")
k(f"{G}.sets.1", "一般的和牌由四组牌加一对将组成。", "一般的和牌由四組牌加一對將組成。", "A winning hand is usually four sets and a pair.", "和了の形は、ふつう 4 つの面子と 1 つの雀頭です。", "화료 형태는 보통 몸통 네 개와 머리 하나입니다.")
k(f"{G}.sets.chow", "顺子", "順子", "Chow", "順子", "슌쯔")
k(f"{G}.sets.chow.text", "同一门序数牌中点数相连的三张牌。", "同一門序數牌中點數相連的三張牌。", "Three tiles in a row of one numbered suit.", "同じ種類の数牌で、数が続いた 3 枚。", "같은 수패에서 숫자가 이어지는 세 장.")
k(f"{G}.sets.pung", "刻子", "刻子", "Pung", "刻子", "커쯔")
k(f"{G}.sets.pung.text", "三张相同的牌。", "三張相同的牌。", "Three of the same tile.", "同じ牌 3 枚。", "같은 패 세 장.")
k(f"{G}.sets.kong", "杠", "槓", "Kong", "槓子", "깡쯔")
k(f"{G}.sets.kong.text", "四张相同的牌。它算作一组，并需要从牌墙尾部补摸一张。", "四張相同的牌。它算作一組，並需要從牌牆尾部補摸一張。",
  "Four of the same tile. It counts as one set, and its maker draws a tile from the back of the wall.", "同じ牌 4 枚。1 つの面子として数え、山の後ろから 1 枚補充します。", "같은 패 네 장. 몸통 하나로 치며, 패산 뒤쪽에서 한 장을 보충합니다.")
k(f"{G}.sets.pair", "将", "將", "Pair", "雀頭", "머리")
k(f"{G}.sets.pair.text", "两张相同的牌。", "兩張相同的牌。", "Two of the same tile.", "同じ牌 2 枚。", "같은 패 두 장.")
k(f"{G}.sets.example", "一手和牌：四组牌加一对将。", "一手和牌：四組牌加一對將。", "A winning hand: four sets and a pair.", "和了の例：4 面子 1 雀頭。", "화료 예: 몸통 넷과 머리 하나.")
k(f"{G}.sets.2", "此外还有以下几种特殊的和牌形式，都只能在没有吃、碰、明杠时组成：", "此外還有以下幾種特殊的和牌形式，都只能在沒有吃、碰、明槓時組成：",
  "There are also these special winning hands, which may only be made with nothing claimed:", "このほか、次のような特殊な和了形があります。いずれも鳴いていないときだけ作れます。", "그 밖에 다음과 같은 특수한 화료 형태가 있습니다. 모두 울지 않았을 때만 만들 수 있습니다.")
k(f"{G}.shape.seven_pairs", "七个对子。", "七個對子。", "Seven pairs.", "7 つの対子。", "또이쯔 일곱 개.")
k(f"{G}.shape.thirteen_orphans", "三门序数牌的一和九，以及七种字牌，各一张，其中一种再多一张作将。",
  "三門序數牌的一和九，以及七種字牌，各一張，其中一種再多一張作將。",
  "One each of the ones and nines of the three suits and the seven honours, and a second of one of them as the pair.",
  "3 種の数牌の 1 と 9、字牌 7 種を 1 枚ずつ、そのうち 1 種をもう 1 枚で雀頭にします。",
  "세 가지 수패의 1과 9, 자패 7종을 한 장씩 모으고, 그중 하나를 한 장 더 모아 머리로 삼습니다.")
k(f"{G}.shape.lesser_honours_knitted", "十四张互不相同、互不相连的牌：序数牌取自分属三门的 147、258、369，其余为字牌。",
  "十四張互不相同、互不相連的牌：序數牌取自分屬三門的 147、258、369，其餘為字牌。",
  "Fourteen different tiles, none next to another: the numbered ones from 147, 258 and 369, each in its own suit, and the rest honours.",
  "互いに異なり、つながらない 14 枚です。数牌は、それぞれ別の種類の 147・258・369 から取り、残りは字牌です。",
  "서로 다르고 이어지지 않는 14장입니다. 수패는 각각 다른 종류의 147·258·369에서 고르고, 나머지는 자패입니다.")
k(f"{G}.shape.knitted_straight", "三门序数牌分别取 147、258、369，共九张，再加一组牌和一对将。",
  "三門序數牌分別取 147、258、369，共九張，再加一組牌和一對將。",
  "147, 258 and 369, each in its own suit, nine tiles in all, with one more set and a pair.",
  "3 種の数牌でそれぞれ 147・258・369 の計 9 枚と、1 面子 1 雀頭です。",
  "세 가지 수패에서 각각 147·258·369 모두 9장과, 몸통 하나와 머리 하나입니다.")

k(f"{G}.turn", "轮到你时", "輪到你時", "Your turn", "自分の番", "내 차례")
k(f"{G}.turn.1", "每人 13 张牌，庄家 14 张并先出牌。开局时，每人依次把花牌放在一旁并补牌。",
  "每人 13 張牌，莊家 14 張並先出牌。開局時，每人依次把花牌放在一旁並補牌。",
  "Everyone gets 13 tiles and the dealer 14, and the dealer discards first. At the start, each player in turn sets their flowers aside and draws again for them.",
  "各自 13 枚、親は 14 枚を持ち、親から捨てます。配牌後、各自が順に花牌を脇に置いて補充します。",
  "각자 13장, 선은 14장을 받고 선이 먼저 버립니다. 시작할 때 각자 차례로 화패를 옆에 두고 보충합니다.")
k(f"{G}.turn.2", "轮到你时，从牌墙摸一张牌，再打出一张。点击一张牌选中，再次点击即可打出。",
  "輪到你時，從牌牆摸一張牌，再打出一張。點擊一張牌選中，再次點擊即可打出。",
  "On your turn, draw a tile from the wall, then discard one. Click a tile to pick it and click it again to discard it.",
  "自分の番では、山から 1 枚引いて 1 枚捨てます。牌をクリックで選び、もう一度クリックで捨てます。",
  "내 차례에는 패산에서 한 장을 뽑고 한 장을 버립니다. 패를 클릭해 고르고, 한 번 더 클릭하면 버립니다.")
k(f"{G}.turn.3", "摸到的牌使你凑成和牌并满 8 番时，可以自摸和牌。", "摸到的牌使你湊成和牌並滿 8 番時，可以自摸和牌。",
  "When the tile you drew completes a hand worth 8 fan, you may win on it, self-drawn.", "引いた牌で 8 番以上の和了形が完成したら、ツモ和了できます。", "뽑은 패로 8번 이상의 화료 형태가 완성되면 쯔모로 화료할 수 있습니다.")
k(f"{G}.turn.4", "手中有四张相同的牌，或摸到自己已碰的牌的第四张时，可以杠牌，并从牌墙尾部补摸一张。",
  "手中有四張相同的牌，或摸到自己已碰的牌的第四張時，可以槓牌，並從牌牆尾部補摸一張。",
  "With four of a tile in your hand, or the fourth of a tile you have punged, you may make a kong and draw a tile from the back of the wall.",
  "同じ牌を 4 枚持っているとき、またはポンした牌の 4 枚目を引いたとき、カンして山の後ろから 1 枚補充できます。",
  "같은 패를 네 장 가졌거나, 퐁한 패의 네 번째 장을 뽑았을 때 깡을 하고 패산 뒤쪽에서 한 장을 보충할 수 있습니다.")

k(f"{G}.claims", "吃、碰、杠与和他人的牌", "吃、碰、槓與和他人的牌", "Claiming a discard", "鳴きとロン", "울기와 론")
k(f"{G}.claims.1", "其他玩家打出牌时，你可以要这张牌：", "其他玩家打出牌時，你可以要這張牌：", "When another player discards, you may claim the tile:", "ほかのプレイヤーが牌を捨てたとき、その牌を取ることができます。", "다른 플레이어가 패를 버리면 그 패를 가져올 수 있습니다.")
k(f"{G}.claims.chow", "吃", "吃", "Chow", "チー", "치")
k(f"{G}.claims.chow.text", "只能吃上家打出的牌，与手中两张牌组成顺子，再打出一张。",
  "只能吃上家打出的牌，與手中兩張牌組成順子，再打出一張。",
  "Only from the player before you: make a run with two tiles in your hand, then discard.",
  "上家の捨て牌だけをチーできます。手の 2 枚と順子を作り、1 枚捨てます。",
  "상가가 버린 패만 치할 수 있습니다. 손의 두 장과 슌쯔를 만들고 한 장을 버립니다.")
k(f"{G}.claims.pung", "碰", "碰", "Pung", "ポン", "퐁")
k(f"{G}.claims.pung.text", "手中有两张相同的牌时，亮出三张再打出一张，之后从你的下家继续。",
  "手中有兩張相同的牌時，亮出三張再打出一張，之後從你的下家繼續。",
  "With two of the tile in your hand, show all three and discard; play goes on from the player after you.",
  "同じ牌を 2 枚持っていれば、3 枚をさらして 1 枚捨てます。続きはあなたの下家からです。",
  "같은 패 두 장이 있으면 세 장을 내놓고 한 장을 버립니다. 이후 하가부터 이어집니다.")
k(f"{G}.claims.kong", "杠", "槓", "Kong", "カン", "깡")
k(f"{G}.claims.kong.text", "手中有三张相同的牌时，亮出四张，并从牌墙尾部补摸一张。",
  "手中有三張相同的牌時，亮出四張，並從牌牆尾部補摸一張。",
  "With three of the tile in your hand, show all four and draw from the back of the wall.",
  "同じ牌を 3 枚持っていれば、4 枚をさらして山の後ろから 1 枚補充します。",
  "같은 패 세 장이 있으면 네 장을 내놓고 패산 뒤쪽에서 한 장을 보충합니다.")
k(f"{G}.claims.2", "和牌：这张牌使你凑成和牌并满 8 番。", "和牌：這張牌使你湊成和牌並滿 8 番。",
  "Win: the tile completes your hand, worth at least 8 fan.", "ロン：その牌で 8 番以上の和了形が完成するとき。", "론: 그 패로 8번 이상의 화료 형태가 완성될 때.")
k(f"{G}.claims.3", "多人要同一张牌时，和牌优先于碰和杠，碰和杠优先于吃。多人都能和牌时，由打牌者之后最近的一家和牌。",
  "多人要同一張牌時，和牌優先於碰和槓，碰和槓優先於吃。多人都能和牌時，由打牌者之後最近的一家和牌。",
  "When several players claim the same tile, a win comes before a pung or kong, and those before a chow. Of several who could win, the first in turn after the discarder does.",
  "複数のプレイヤーが同じ牌を取ろうとしたときは、ロンがポン・カンより、ポン・カンがチーより優先します。複数人がロンできるときは、捨てた人から順番が近い 1 人だけが和了します。",
  "여러 명이 같은 패를 원하면 론이 퐁·깡보다, 퐁·깡이 치보다 우선합니다. 여러 명이 론할 수 있으면 버린 사람 다음 순서에서 가장 가까운 한 명만 화료합니다.")
k(f"{G}.claims.4", "有人将已碰的牌补杠时，能用这张牌和牌的玩家可以抢杠和牌。牌墙最后一张牌打出后，只能和牌，不能吃、碰、杠。",
  "有人將已碰的牌補槓時，能用這張牌和牌的玩家可以搶槓和牌。牌牆最後一張牌打出後，只能和牌，不能吃、碰、槓。",
  "When someone adds the fourth tile to a pung, a player who would win on it may rob the kong. The last tile of the wall, once discarded, may only be won on, not claimed for a set.",
  "誰かがポンした牌に加槓するとき、その牌で和了できるプレイヤーは搶槓できます。山の最後の牌が捨てられたときは、ロンだけができ、鳴くことはできません。",
  "누군가 퐁한 패에 가깡할 때, 그 패로 화료할 수 있는 플레이어는 창깡할 수 있습니다. 패산의 마지막 패가 버려지면 론만 할 수 있고 울 수는 없습니다.")

k(f"{G}.fan", "番种与计番", "番種與計番", "Counting fan", "番の数え方", "번 계산")
k(f"{G}.fan.1", "和牌时，这手牌符合的番种都计算在内。若一个番种已经包含了另一个，被包含的番种不再重复计算，例如计清一色时不再计无字。",
  "和牌時，這手牌符合的番種都計算在內。若一個番種已經包含了另一個，被包含的番種不再重複計算，例如計清一色時不再計無字。",
  "A winning hand counts every fan it has, except those a bigger one already includes: a full flush, for example, does not count no honours as well.",
  "和了した手は、当てはまる番種をすべて数えます。ただし、ある番種がほかの番種を含むときは、含まれる方を重ねて数えません。たとえば清一色のときは無字を数えません。",
  "화료한 패는 해당하는 번종을 모두 셉니다. 다만 어떤 번종이 다른 번종을 포함하면 포함되는 쪽은 다시 세지 않습니다. 예를 들어 청일색이면 무자는 세지 않습니다.")
k(f"{G}.fan.2", "同一手牌有多种拆分方法时，按得番最多的拆法计算。", "同一手牌有多種拆分方法時，按得番最多的拆法計算。",
  "When the hand can be split into sets in more than one way, the way worth the most fan counts.", "手の分け方が複数あるときは、番数が最も多くなる分け方で数えます。", "패를 나누는 방법이 여러 가지이면 번수가 가장 많은 방법으로 셉니다.")
k(f"{G}.fan.3", "顺子之间、刻子之间组成的番种，如一般高、喜相逢、连六、老少副，每组牌与另一组牌只能组合一次，同一组牌也不能重复组成相同的番种。",
  "順子之間、刻子之間組成的番種，如一般高、喜相逢、連六、老少副，每組牌與另一組牌只能組合一次，同一組牌也不能重複組成相同的番種。",
  "Fan that two sets make together, such as pure double chow, mixed double chow, short straight or two terminal chows, count each pair of sets once, and no set makes the same fan twice.",
  "一般高・喜相逢・連六・老少副のように面子どうしで作る番種は、2 つの面子の組み合わせごとに 1 回だけ数え、同じ面子で同じ番種を重ねて作ることはできません。",
  "일반고·희상봉·연륙·노소부처럼 몸통끼리 만드는 번종은 두 몸통의 조합마다 한 번만 세며, 같은 몸통으로 같은 번종을 거듭 만들 수 없습니다.")
k(f"{G}.fan.4", "和牌至少要有 8 番，花牌不计在内。", "和牌至少要有 8 番，花牌不計在內。", "A winning hand needs at least 8 fan, flowers not counted.", "和了には、花牌を除いて 8 番以上が必要です。", "화료하려면 화패를 제외하고 8번 이상이 필요합니다.")
k(f"{G}.fan.5", "除花牌外没有任何番种的和牌，计为无番和，值 8 番。", "除花牌外沒有任何番種的和牌，計為無番和，值 8 番。",
  "A winning hand with no fan at all besides flowers is a chicken hand, worth 8 fan.", "花牌以外の番種が何もない和了は無番和となり、8 番です。", "화패 외에 아무 번종도 없는 화료는 무번화로, 8번입니다.")
k(f"{G}.fan.6", "边张、坎张、单钓将只在这手牌只听一张牌时计算。", "邊張、嵌張、單釣將只在這手牌只聽一張牌時計算。",
  "An edge wait, closed wait or single wait only counts when the hand waited on that one tile alone.", "辺張・嵌張・単釣将は、その 1 種類の牌だけを待っていたときに限り数えます。", "변장·감장·단조장은 그 한 가지 패만 기다렸을 때만 셉니다.")
k(f"{G}.fan.7", "以下是几种常见的凑满 8 番的方式。", "以下是幾種常見的湊滿 8 番的方式。", "Some common ways to reach 8 fan:", "8 番に届くよくある形をいくつか挙げます。", "8번을 채우는 흔한 형태 몇 가지입니다.")
k(f"{G}.hand.mixed_straight", "8 番：三门序数牌各一组顺子，分别为 123、456、789。", "8 番：三門序數牌各一組順子，分別為 123、456、789。",
  "8 fan: 123, 456 and 789, each in a different suit.", "8 番：3 種の数牌でそれぞれ 123・456・789 の順子。", "8번: 세 가지 수패에서 각각 123·456·789 슌쯔.")
k(f"{G}.hand.mixed_triple_chow", "8 番：三门序数牌中点数相同的三组顺子。", "8 番：三門序數牌中點數相同的三組順子。",
  "8 fan: the same run in all three suits.", "8 番：3 種の数牌で同じ数の順子。", "8번: 세 가지 수패에서 숫자가 같은 슌쯔.")
k(f"{G}.hand.all_pungs", "6 番：四组都是刻子或杠。再有幺九刻、箭刻或暗刻，通常即可满 8 番。", "6 番：四組都是刻子或槓。再有么九刻、箭刻或暗刻，通常即可滿 8 番。",
  "6 fan: every set a pung or kong. A pung of terminals or honours, a dragon pung or concealed pungs usually bring it to 8.",
  "6 番：4 面子すべてが刻子か槓子。幺九刻・箭刻・暗刻があれば、たいてい 8 番に届きます。",
  "6번: 몸통 넷이 모두 커쯔나 깡쯔. 요구각·전각·암각이 더해지면 대개 8번이 됩니다.")
k(f"{G}.hand.half_flush", "6 番：只有一门序数牌和字牌。再有箭刻、风刻等，即可满 8 番。", "6 番：只有一門序數牌和字牌。再有箭刻、風刻等，即可滿 8 番。",
  "6 fan: one numbered suit and honours only. A dragon or wind pung brings it to 8.",
  "6 番：1 種の数牌と字牌だけ。箭刻や風牌の刻子などがあれば 8 番に届きます。",
  "6번: 수패 한 종류와 자패만. 전각이나 풍패 커쯔가 더해지면 8번이 됩니다.")
k(f"{G}.hand.full_flush", "24 番：只有一门序数牌，不含字牌。", "24 番：只有一門序數牌，不含字牌。", "24 fan: one numbered suit, no honours.", "24 番：1 種の数牌だけで、字牌なし。", "24번: 수패 한 종류만, 자패 없음.")
k(f"{G}.hand.pure_straight", "16 番：同一门序数牌的 123、456、789。", "16 番：同一門序數牌的 123、456、789。", "16 fan: 123, 456 and 789 of one suit.", "16 番：同じ種類の数牌の 123・456・789。", "16번: 같은 수패의 123·456·789.")
k(f"{G}.hand.all_types", "6 番：万、筒、条、风牌、箭牌五种牌都有。", "6 番：萬、筒、條、風牌、箭牌五種牌都有。",
  "6 fan: characters, dots, bamboo, winds and dragons all in the hand.", "6 番：萬子・筒子・索子・風牌・三元牌がすべてある。", "6번: 만수·통수·삭수·풍패·삼원패가 모두 있음.")

k(f"{G}.list", "番种一览", "番種一覽", "Every fan", "番種一覧", "번종 일람")
k(f"{G}.list.intro", "全部番种按番数列出如下。和牌后，结算画面会列出这手牌计入的每个番种及其番数。",
  "全部番種按番數列出如下。和牌後，結算畫面會列出這手牌計入的每個番種及其番數。",
  "Every fan, by what it is worth. After a win, the result lists each fan the hand counted and what it is worth.",
  "すべての番種を番数ごとに挙げます。和了すると、結果画面にその手で数えた番種と番数が表示されます。",
  "모든 번종을 번수별로 나열합니다. 화료하면 결과 화면에 그 패에서 센 번종과 번수가 표시됩니다.")

k(f"{G}.points", "计分", "計分", "Points", "得点", "점수")
k(f"{G}.points.1", "和牌者得到基础分 8 分，另加这手牌的番数。", "和牌者得到基礎分 8 分，另加這手牌的番數。",
  "The winner scores a base of 8 points, plus the fan of the hand.", "和了した人は、基本の 8 点に手の番数を加えた点を得ます。", "화료한 사람은 기본 8점에 패의 번수를 더한 점수를 얻습니다.")
k(f"{G}.points.2", "和他人打出的牌：打出这张牌的玩家支付 8 分加番数，另外两名玩家各支付 8 分。",
  "和他人打出的牌：打出這張牌的玩家支付 8 分加番數，另外兩名玩家各支付 8 分。",
  "On a discard: the discarder pays 8 points and the fan, and the other two pay 8 each.", "ロン：捨てた人が 8 点と番数を払い、残りの 2 人は 8 点ずつ払います。", "론: 버린 사람이 8점과 번수를 내고, 나머지 두 사람은 8점씩 냅니다.")
k(f"{G}.points.3", "自摸：其他三名玩家各支付 8 分加番数。", "自摸：其他三名玩家各支付 8 分加番數。", "Self-drawn: the other three each pay 8 points and the fan.", "ツモ：ほかの 3 人がそれぞれ 8 点と番数を払います。", "쯔모: 다른 세 사람이 각각 8점과 번수를 냅니다.")
k(f"{G}.points.4", "抢杠和牌时，由补杠的玩家按和他人打出的牌支付。牌墙摸完而无人和牌时，本局不计分。",
  "搶槓和牌時，由補槓的玩家按和他人打出的牌支付。牌牆摸完而無人和牌時，本局不計分。",
  "When a kong is robbed, the player making it pays as for a discard. When the wall runs out and nobody wins, the hand scores nothing.",
  "搶槓のときは、加槓した人がロンと同じように払います。山が尽きて誰も和了しなかった局は、得点の移動がありません。",
  "창깡일 때는 가깡한 사람이 론과 같이 냅니다. 패산이 다 떨어지고 아무도 화료하지 못한 국은 점수가 없습니다.")

k(f"{G}.game", "一场的进行", "一場的進行", "Hands and rounds", "局の進み方", "국의 진행")
k(f"{G}.game.1", "每局结束后，不论谁和牌，庄家都轮给下家。庄家为东，其余玩家依次为南、西、北，这是各自的门风。",
  "每局結束後，不論誰和牌，莊家都輪給下家。莊家為東，其餘玩家依次為南、西、北，這是各自的門風。",
  "After every hand, whoever won, the deal passes to the next player. The dealer is east and the others south, west and north in turn: each player's seat wind.",
  "各局が終わると、誰が和了しても親は下家に移ります。親が東、ほかのプレイヤーが順に南・西・北で、これが各自の門風です。",
  "각 국이 끝나면 누가 화료하든 선은 하가로 넘어갑니다. 선이 동이고, 나머지 플레이어는 차례로 남·서·북이며, 이것이 각자의 문풍입니다.")
k(f"{G}.game.2", "每四局为一圈，圈风依次为东、南、西、北。与自己门风或圈风相同的风牌刻子，计门风刻或圈风刻。",
  "每四局為一圈，圈風依次為東、南、西、北。與自己門風或圈風相同的風牌刻子，計門風刻或圈風刻。",
  "Every four hands make a round, whose wind is east, then south, west and north. A pung of your seat wind or the round's wind counts as seat wind or prevalent wind.",
  "4 局で 1 圏となり、圏風は東・南・西・北の順です。自分の門風か圏風と同じ風牌の刻子は、門風刻か圏風刻になります。",
  "4국이 한 권이 되며, 권풍은 동·남·서·북 순서입니다. 자기 문풍이나 권풍과 같은 풍패의 커쯔는 문풍각이나 권풍각이 됩니다.")
k(f"{G}.game.3", "每局结束后显示结算，所有玩家确认后开始下一局。最后一局结束时，一场结束，总分最高者获胜。",
  "每局結束後顯示結算，所有玩家確認後開始下一局。最後一局結束時，一場結束，總分最高者獲勝。",
  "After each hand the result is shown, and the next hand starts once everyone has looked at it. After the last hand the game ends, and whoever has the most points wins.",
  "各局の後に結果が表示され、全員が確認すると次の局が始まります。最後の局が終わると試合終了で、合計点が最も多い人の勝ちです。",
  "각 국이 끝나면 결과가 표시되고, 모두가 확인하면 다음 국이 시작됩니다. 마지막 국이 끝나면 판이 끝나며, 총점이 가장 높은 사람이 이깁니다.")

k(f"{G}.help", "辅助提示", "輔助提示", "Help at the table", "補助表示", "보조 표시")
k(f"{G}.help.1", "牌桌会帮助你看清自己的手牌：", "牌桌會幫助你看清自己的手牌：", "The table helps you read your hand:", "卓では、自分の手を把握する手助けをします。", "테이블은 내 패를 파악하도록 돕습니다.")
k(f"{G}.help.2", "听牌后，所听的牌显示在你的名字旁边，每张牌旁的数字是尚未出现的张数。显示为灰色的牌，即使和牌也不满 8 番。",
  "聽牌後，所聽的牌顯示在你的名字旁邊，每張牌旁的數字是尚未出現的張數。顯示為灰色的牌，即使和牌也不滿 8 番。",
  "Once you are ready, the tiles you wait on show beside your name, each with how many of it are still unseen. A greyed tile would not make 8 fan.",
  "聴牌すると、待ち牌が名前の横に表示されます。牌の横の数字は、まだ見えていない枚数です。灰色の牌は、和了しても 8 番に届きません。",
  "텐파이하면 기다리는 패가 이름 옆에 표시됩니다. 패 옆의 숫자는 아직 보이지 않은 장수입니다. 회색 패는 화료해도 8번이 되지 않습니다.")
k(f"{G}.help.3", "轮到你出牌时，打出后可以听牌的牌上方有标记：绿色表示至少有一张所听的牌可以和牌，黄色表示所听的牌都不满 8 番。",
  "輪到你出牌時，打出後可以聽牌的牌上方有標記：綠色表示至少有一張所聽的牌可以和牌，黃色表示所聽的牌都不滿 8 番。",
  "On your turn, a mark over a tile says discarding it leaves you ready: green when some wait would win, yellow when none would make 8 fan.",
  "自分の番では、捨てると聴牌になる牌の上に印が付きます。緑は和了できる待ちがあること、黄は待ちがどれも 8 番に届かないことを示します。",
  "내 차례에는 버리면 텐파이가 되는 패 위에 표시가 붙습니다. 초록은 화료할 수 있는 대기패가 있다는 뜻이고, 노랑은 어느 대기패도 8번이 되지 않는다는 뜻입니다.")
k(f"{G}.help.4", "选中一张牌或将指针移到牌上，可以看到打出它之后所听的牌。", "選中一張牌或將指標移到牌上，可以看到打出它之後所聽的牌。",
  "Pick a tile, or point at it, to see what you would wait on after discarding it.", "牌を選ぶかカーソルを合わせると、それを捨てた後の待ち牌が表示されます。", "패를 고르거나 커서를 올리면, 그 패를 버린 뒤의 대기패가 표시됩니다.")
k(f"{G}.help.5", "可以和牌时，按钮上会显示这手牌的番数。", "可以和牌時，按鈕上會顯示這手牌的番數。", "When you can win, the button shows what the hand is worth.", "和了できるときは、ボタンに手の番数が表示されます。", "화료할 수 있을 때는 버튼에 패의 번수가 표시됩니다.")

for value, keys in VALUES.items():
    texts = []
    for i, lang in enumerate(LANGS):
        head, sep, end = LIST_FORMAT[lang]
        texts.append(head % value + sep.join(FANS[f][i] for f in keys) + end)
    S[f"{G}.list.{value}"] = tuple(texts)

# Sichuan's game is one of three now, so its name says which.
k("tukuai.game.mahjong", "四川麻将", "四川麻將", "Sichuan Mahjong", "四川麻雀", "쓰촨 마작")

HK = {}

if __name__ == "__main__":
    update(S, HK)
