#!/bin/zsh
# Turns the screenshots ShowcaseGameTest takes into the README's pictures: each language's
# in-world shot of every game, the clip in English and Simplified Chinese as animated WebP,
# and the English clip as a GIF for the mod pages. Needs cwebp, img2webp and ffmpeg.
#
#   TUKUAI_SHOWCASE=en_us,zh_cn,zh_tw,ja_jp,ko_kr ./gradlew :mc26:fabric-26.1.2:runClientGameTest
#   tools/showcase_images.sh
#
# A run with TUKUAI_SHOWCASE_GAMES=cave takes only that game's shots; only the pictures it took are replaced.
set -e
cd "${0:A:h}/.."
shots=mc26/versions/fabric-26.1.2/build/run/clientGameTest/screenshots
frames=$(mktemp -d)
trap 'rm -r "$frames"' EXIT
for pair in en_us:en zh_cn:zh-CN zh_tw:zh-TW ja_jp:ja ko_kr:ko; do
  lang=${pair%%:*}
  out=docs/images/${pair#*:}
  for game in doudizhu:ddz paodekuai:pdk guandan:gd tutu:tutu uno:uno cave:cave; do
    [[ -e $shots/showcase-$lang-${game%%:*}-world.png ]] || continue
    cwebp -quiet -q 80 -m 6 $shots/showcase-$lang-${game%%:*}-world.png -o $out/${game#*:}-world.webp
  done
  # The clip shows little text, so the other languages share these two.
  [[ $lang == en_us || $lang == zh_cn ]] || continue
  [[ -e $shots/showcase-$lang-clip-000.png ]] || continue
  mkdir $frames/$lang
  ffmpeg -loglevel error -framerate 20 -i $shots/showcase-$lang-clip-%03d.png -vf "scale=960:-1:flags=lanczos" $frames/$lang/%03d.png
  # Each frame keeps only what changed from the one before, so the clip stays well under a megabyte.
  (cd $frames/$lang && img2webp -loop 0 -lossy -q 70 -m 4 -d 50 -min_size *.png -o $OLDPWD/$out/clip.webp > /dev/null 2>&1)
done
[[ -e $shots/showcase-en_us-clip-000.png ]] || exit 0
ffmpeg -y -loglevel error -framerate 20 -i $shots/showcase-en_us-clip-%03d.png \
  -vf "scale=800:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
  -loop 0 docs/images/clip.gif
