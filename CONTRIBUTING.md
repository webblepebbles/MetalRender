# Contributing to MetalRender

Thank you for your support in improving MetalRender!

Your changes should be understandable, safe, and verified on a Mac before you submit a PR (Pull Request). Please review this document before submitting any PR.

## Before you start

- Check the README and existing documentation to understand the project and its supported environment
- Explain the problem your changes solve and any visible behavior changes
- **If you do NOT have a Mac (YOU CANNOT test if you do not have a Mac), do not submit a pull request** unless you are making changes to documentation
- **YOU MUST run a test**
- There are no naming conventions, naming styles, code styles, or other repository-specific styling requirements in MetalRender. You do not need to specify or follow any in your PR
- There are no restrictions on the use of AI to make a PR, as long as a human has reviewed the code and a test has been run successfully

## Development and verification

- Use the Java version and dependencies specified by the project, and run the game with `./gradlew runclient`
- Run the game on a supported macOS and Apple GPU setup and test the changed behavior. A successful compile alone is not sufficient. A person must perform and confirm a runtime test before the change is considered ready
- Include the steps you followed and the outcome in your pull request. If a required test could not be run, state why and identify what still needs verification. **DO NOT present untested behavior as tested. That is deceptive marketing (I'll probably notice the issue before shipping it, but make sure you check it yourself)!**

## Human review

- Every change must be read and reviewed by a person before it is merged. The reviewer should understand what the code does, check that it fits the project, and look for unintended effects
- Contributors should read their own complete diff before submitting it. Remove unrelated edits and temporary files
- A passing build or test does not replace human review or runtime testing

## Safety and security

Changes must not introduce dangerous, deceptive, or unauthorised behavior (ILLEGAL!!). In particular:

- Do not add code that deletes, encrypts, corrupts, or uploads user files or data without a clear, necessary, user-visible feature and explicit user action
- Do not add code that counts as a cheat (not intentionally, at least), gives players a competitive advantage over those without a comparable program, or enables in-game interactions that would normally be impossible
- Do not include telemetry that collects or transmits users' sensitive device information, such as IP addresses, MAC addresses, or other personally identifiable information
- Do not execute downloaded or user-provided content, run hidden commands, bypass operating-system security, or weaken permissions and security controls
- Do not include secrets, private keys, access tokens, or personal data in source code, logs, test fixtures, or commits. Make sure to tell your AI agent (should you be using one) not to include your personal details wherever possible

If you find a security issue (which you really can't, since MetalRender isn't exactly the kind of software that has... security issues. It's a Minecraft mod. What is there to secure?), please report it.

## Code and documentation

- Keep changes as small and practical as possible
- Update documentation when behavior, setup, commands, configuration, or compatibility changes
- Do not claim support for hardware, game versions, or configurations that you have not verified
- When fixing issues, explain the issue in detail first

## Pull requests

A pull request should include:

- A concise description of the change and the reason for it
- The relevant issue or discussion, if there is one
- Build and test steps, including the human-run in-game verification and its result
- Any limitations, compatibility effects, or risks reviewers should know about

You should respond to review feedback (if you don't respond, I'll just close your PR) and update the pull request until the change is understood, safely verified, and ready to merge.

---

Thank you for your continued support in MetalRender's development!

---

# MetalRender への貢献について

MetalRender の改善にご協力いただき、ありがとうございます :D

プルリクエスト（PR）を提出する前に、変更内容がわかりやすく、安全であり、Mac 上で動作確認されていることをたしかめてください

PR を提出する前に、必ずこのドキュメントをお読みください

## はじめに

- README と既存のドキュメントを確認し、プロジェクトの内容とサポート対象の環境を理解してください
- 変更によって解決される問題と、ユーザーから見える動作の変化を説明してください
- **Mac を持っていない場合（Mac がなければテストできません）、プルリクエストを提出しないでください**。ただし、ドキュメントのみの変更は例外です
- **テストは必ず実行してください**
- MetalRender には、命名規則やコードスタイルなど、リポジトリ固有のスタイル要件はありません。PR でこれらのルールを指定したり、特定のスタイルにしたがったりする必要はありません
- AI を使って PR を作成することに制限はありません。ただし、必ず人がコードを確認・レビューし、テストが正常に完了している必要があります

## 開発と動作確認

- プロジェクトで指定されている Java のバージョンと依存関係を使用し、`./gradlew runclient` でゲームを実行してください
- サポート対象の macOS と Apple GPU の環境でゲームを実行し、変更した部分が正しく動くかをたしかめてください。コンパイルが成功しただけでは不十分です。変更が提出可能と判断される前に、必ず人が実際の環境でテストを行い、その結果を確認する必要があります
- PR には、実施したテストの手順と結果を書いてください。必要なテストを実行できなかった場合は、その理由と、まだ確認が必要な項目を明記してください。**テストしていない動作を、テスト済みであるかのように説明しないでください。それは人をだますような宣伝です（リリース前に私が問題に気づくとは思いますが、自分でも必ず確認してください）！**

## 人によるレビュー

- すべての変更は、マージされる前に必ず人が確認・レビューする必要があります。レビュアーは、コードの動作を理解し、プロジェクトに合っているかをたしかめるとともに、意図しない影響がないかを確認してください
- 貢献者は、PR を提出する前に、自分が行った変更の差分（diff）全体を確認してください。関係のない変更や一時ファイルは削除してください
- ビルドやテストが成功していても、人によるレビューや実際のゲーム内での動作確認を省略することはできません

## 安全性とセキュリティ

変更によって、危険な動作、ユーザーをだますような動作、または許可されていない動作を追加してはいけません（違法です！！）

特に、以下のルールを守ってください

- 明確で必要な機能としてユーザーにわかる形で提供され、ユーザー自身が明示的に操作する場合を除き、ユーザーのファイルやデータを削除、暗号化、破損、またはアップロードするコードを追加しないでください
- チートに該当するコード（少なくとも意図的には！）、同等のプログラムを使っていないプレイヤーに対して不公平な優位性を与えるコード、または通常は不可能なゲーム内操作を可能にするコードを追加しないでください
- IP アドレス、MAC アドレス、その他個人を特定できる情報など、ユーザーの機密性の高いデバイス情報を収集・送信するテレメトリー機能を追加しないでください
- ダウンロードしたコンテンツやユーザーが提供したコンテンツを実行したり、ユーザーに知らせずにコマンドを実行したり、OS のセキュリティ機能を回避したり、アクセス権限やセキュリティ設定を弱めたりしないでください
- ソースコード、ログ、テスト用データ、コミットに、シークレット、秘密鍵、アクセストークン、個人情報を含めないでください。AI エージェントを使う場合は、できるだけ個人情報を含めないよう、エージェントにも明確に指示してください

もしセキュリティ上の問題を見つけた場合は（まあ、見つからないと思いますが。MetalRender はそもそも……セキュリティ上の問題が起きるようなソフトじゃないですし。Minecraft の Mod ですよ？ いったい何を守る必要があるんですか？）、報告してください

## コードとドキュメント

- 変更は、できるだけ小さく、実用的なものにしてください
- 動作、セットアップ手順、コマンド、設定、または互換性が変わる場合は、ドキュメントも更新してください
- 動作確認をしていないハードウェア、ゲームのバージョン、または設定について、サポートしていると主張しないでください
- 問題を修正する場合は、まずその問題の詳細を説明してください

## プルリクエスト

プルリクエストには、以下の内容を含めてください

- 変更内容とその理由についての短くわかりやすい説明
- 関連する Issue や Discussion がある場合は、その参照
- ビルドとテストの手順、および人が実際にゲーム内で行った動作確認とその結果
- レビュアーが知っておくべき制限事項、互換性への影響、またはリスク

レビューで寄せられたフィードバックには対応してください（返事がなければ、こちらで PR をクローズします）。変更内容が十分に理解され、安全性と動作が確認され、マージできる状態になるまで PR を更新してください

---

MetalRender の開発に引き続きご協力いただき、ありがとうございます