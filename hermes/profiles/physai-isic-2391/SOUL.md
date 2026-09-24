# physai-isic-2391 — 耐火物製造業 の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-2391`、ISIC 2391 耐火物製造業）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: README に Robotics premise の節は無い。Scope が名指す工場 —— シャモット・アルミナ・マグネシア・シリカの調合、プレス/鋳込み成形、乾燥、トンネル窯/シャトル窯での焼成 —— の物理的な仕事（成形品の芯までの焼成、断熱ライニングの外殻温度、生素地の窯車への窯詰め）をロボットの仕事として置いた。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:brick-firing-soak` | thermal | 焼成帯 1450 °C（放射を表面熱伝達に集約）で、900 °C で入った成形品の中心が 1400 °C に達するまで（半厚、中心断熱） | 中心 1400 °C 到達時間 | 14400 s = 4 h（estimate） |
| `:lining-cold-face` | thermal | 稼働面 1300 °C の厚さ 300 mm の断熱ライニング、20 日後の外殻温度（外気 25 °C）。sweep は断熱材の熱伝導率 | 外殻温度 | 80 °C（estimate） |
| `:kiln-car-setting` | manipulator | 窯詰めロボットがプレスの取出しベルトから生素地を窯車の積み模様へ置く（2 リンクアーム） | 肩関節ピークトルク | 300 N·m（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/refractorymfg/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する）。


## 測って分かったこと・限界（成長の第一候補）

1. **焼成**: 半厚 20 mm で 1488 s、32.5 mm（並形れんが 65 mm 厚）で 3136 s、50 mm で 6376 s、75 mm で 12928 s、100 mm で 21741 s。焼成帯 4 h で芯まで焼けるのは半厚 **79.6 mm** まで —— 大型異形品は焼成帯を長くするか送り速度を落とす必要がある。
2. **ライニング外殻**: 20 日後の外殻温度は熱伝導率 0.10 W/mK で 59.5 °C、0.15 で 76.0 °C、0.20 で 92.1 °C、0.40 で 152.5 °C（0.2 以上は定常）。80 °C 以下に保てるのは **0.162 W/mK 以下**。最初は熱伝導率 0.3 で厚さを振ったが 230 mm で 150 °C、460 mm でも 90.6 °C で、単層の断熱れんがでは足りない —— 実炉の多層ライニング（稠密れんが + 断熱れんが + バックアップ断熱材）は solver が単層なので表せない。
3. **窯詰め**: 肩トルクは 3.5 kg（並形 1 丁）で 95.8 N·m、14 kg で 178.6 N·m、21 kg で 233.8 N·m。300 N·m に達するのは **29.4 kg**。
4. **estimate のままの値**（成長候補）: 焼成帯滞留 4 h と窯内温度（窯メーカーの焼成曲線）、成形品の物性と放射の等価熱伝達係数、外殻 80 °C（炉の設計基準・接触やけどの基準）、断熱材の熱伝導率（JIS R 2616 / ISO 8894 等の測定値・カタログ値で置き換える）、肩トルク 300 N·m（アームの仕様書）。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-2391 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-2391 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
