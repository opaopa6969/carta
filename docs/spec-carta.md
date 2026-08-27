# Carta specification

この文書は、Carta の外部契約と、変更時に確認が必要な前提を記録する。実装から機械的に読み取れるAPI一覧の転記ではなく、現時点でテストまたは公開ドキュメントから確認できる契約に限定する。

## 外部契約

- 対象は Java 21 以上の、依存ゼロ（実行時）の状態機械ライブラリである。
- 定義は `Carta.define(...).build()` で確定し、`build()` に失敗した定義は実行できない。
- 状態は階層化できる。複合状態には初期子が必要で、実行時の現在状態は葉として扱う。
- Event 遷移は `send(Event)`、型付き外部データによる External 遷移は `resume(Map<Class<?>, Object>)` で起動する。
- External 遷移の `requires()` は、同じsource stateにある複数guardの振り分けに使われる。入力に必要な型が無いguardは評価しない。
- `Accepted` のデータはコンテキストに追加され、`Rejected` は遷移しない。`Expired` の呼び出し側での扱いは要確認である。
- Event / External 遷移の後は Auto / Branch 遷移を自動実行し、terminal state または外部入力待ちで停止する。自動連鎖の上限は10である。
- terminal stateからの遷移は定義できない。entry/exit actionは階層のLCAを境に実行される。
- `FlowStore` は `FlowInstance` の保存・読出し・削除を担うSPIであり、同梱実装はインメモリのみである。

## 壊してはいけない契約

- `org.unlaxer` の公開型・メソッド名、`ResumeResult` の値、`CartaException` のerror codeは利用側が参照するため、互換性判断なしに変更しない。
- 状態名とEvent名は定義・ログ・Mermaid出力で外部から識別子として使われるため、暗黙に正規化しない。
- `requires()` / `produces()` はデータフロー検証と図示の入力であり、実行時に参照するコンテキストのキー型と一致させる。

## 判断待ち（未確定の設計）

- ~~`requires()` の検証単位が「全定義に存在するか」なのか「実行可能な上流経路で生成されるか」は未確定。現実装は前者、READMEは後者を示していた。~~ → **確定（#5）**: `build()` の `validateDataFlowChain` は「全定義内に producer が存在するか」の全体集合チェックを正とする。README の "upstream" 表現はこの挙動に合わせて修正済み。経路ベースの到達可能性は `DataFlowGraph.availableAt(state)` で診断提供するが `build()` の検証には用いない。厳密な経路検証の導入は別 issue で扱う。
- guard失敗回数、作成・更新時刻を `FlowInstance` の永続化境界に含めるかは未確定。現実装にはフィールドがあるが、engineのexport/restoreとの連携は未完である。
- `GuardOutput.Expired` を `resume()` の結果として呼び出し側へ伝える契約は未確定。現実装はExpiredを個別結果に変換せず、最終的に `REJECTED` を返す。

## 要確認の設計理由

なぜ階層Statechartとtramli互換の型付きデータフローを一つの定義に組み合わせるのか、また、なぜ依存ゼロ・Java 21を制約とするのかは、現存資料から根拠を確認できない。将来の互換性判断に影響するため、推測で補わない。
