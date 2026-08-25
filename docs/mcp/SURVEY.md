# Carta — MCP 化調査（Phase 1）

## 概要

Carta は **Harel Statechart の階層状態・entry/exit・イベントバブリング**と、**tramli のデータフロー検証（requires/produces 契約・auto-chain・ビルド時 7 項目検証）**を統合した Java ライブラリ。依存ゼロ・Java 21+・Maven Central 公示済み（`org.unlaxer:carta:1.0.0`）。HTTP API・CLI・MCP・サーバプロセスは一切持たない。

## 判定と理由

**判定: `skip`（対応しない）**

Carta の核心は Java DSL で状態機械を定義することにある。`StateProcessor`・`TransitionGuard`・`BranchProcessor` は Java インターフェースであり、`requires()`/`produces()` は `Class<?>` 型で宣言される。状態機械の定義・検証・実行のいずれも **Java コードのコンパイルが前提**であり、MCP 経由で JSON 等の動的入力から意味のある状態機械を構築・実行できない。

- 検証・Mermaid 生成・データフロー分析はすべて `StateMachine` オブジェクト（Java DSL で構築）が必要
- プロセッサ・ガードのビジネスロジックが Java ラムダ/インターフェースそのものであり、RPC 経由で表現・安全に実行する手段がない
- 依存ゼロ・起動 μs 単位・Maven Central 公開済みで、Java プロジェクトは `pom.xml` に 1 行追加するだけで済む（常駐サーバにする価値がない）

将来 Carta が **宣言的（JSON/YAML）状態機械定義**をサポートすれば `library-serve` を再検討できるが、現時点では該当しない。

## 公開候補

| kind | name | io | 副作用 | 長時間 | 備考 |
|------|------|----|--------|----|------|
| tool | validate | StateMachine 定義 → 検証結果（7項目） | none | false | Java DSL 構築前提。`StateMachine.validate()` (StateMachine.java:233) |
| tool | to_mermaid | StateMachine → Mermaid stateDiagram-v2 | none | false | 同上。`StateMachine.toMermaid()` (StateMachine.java:148) |
| tool | to_data_flow_mermaid | StateMachine → Mermaid flowchart | none | false | 同上。`StateMachine.toDataFlowMermaid()` (StateMachine.java:175) |
| tool | analyze_data_flow | StateMachine → DataFlowGraph | none | false | 同上。`StateMachine.dataFlowGraph()` (StateMachine.java:141) |
| resource | spec | — | — | — | `carta://spec`（能力の機械可読仕様） |
| resource | guide | — | — | — | `carta://guide`（使い方） |

※ すべての tool は StateMachine オブジェクト（Java DSL で構築）が入力に必要なため、MCP 経由で利用できない。skip 判定の根拠。

## 組み合わせ例

skip 判定のため、現時点で意味のある組み合わせ例はない。参考として、将来宣言的定義をサポートした場合の想定:

1. `carta__validate` → `carta__to_mermaid`（設計したフローを検証して図を生成しドキュメントに埋め込む）
2. `tramli-appspec` が生成したフロー定義 JSON → `carta__validate` で検証 → `carta__to_data_flow_mermaid` でデータ依存を可視化
3. `nanori__parse`（名乗り解析）の結果を `carta__send_event` で状態機械に取り込み、ワークフローを進行させる

## 依存と協調

| repo | 方向 | 能力 | 現存 | 備考 |
|------|------|------|------|------|
| tramli | provides_to | Carta は tramli の上位互換（Harel 階層 + tramli データフロー検証） | Yes | tramli も library で MCP なし。相互の MCP 協調は不要 |
| tramli-appspec | depends_on | tramli ファミリ向け spec-driven フロー設計。Carta は superset だが appspec 連携は現状 tramli 向け | Yes | 将来的に Carta 向け拡張の可能性があるが Phase 1 では追わない |

Phase 1 では issue を立てない。Phase 2 が issue-hub で協調する。

## ライブラリのサーバ化

該当しない（`library_serve.needed: false`）。

理由: Carta の Java DSL はプロセッサ・ガードが Java コードそのものであり、宣言的定義なしにサーバ化しても RPC 経由で状態機械を定義・実行できない。仮にサーバ化する場合は新規 MCP サーバ・healthz・PORT 環境変数・`volta.service.json`・systemd unit・JVM 常駐プロセスの実装が必要で、推定工数は L。

## リスク

- Carta のプロセッサ・ガードは Java コード（ラムダ/インターフェース）であり、MCP 経由で動的定義するには任意のコード実行が必要になる（安全でない）
- もし宣言的定義を追加する場合、プロセッサのビジネスロジックをどう表現するかが根本的な設計課題
- JVM 常駐プロセスの運用コスト（ポート・メモリ・起動時間）が、μs 単位のライブラリ呼び出しに対して不釣り合い

## 持ち主への質問

- Carta に JSON/YAML による宣言的状態機械定義（プロセッサを除く構造検証・図生成のみ、または式言語によるガード/アクション）を追加する予定があるか？ これがあれば `library-serve` を再検討できる。
