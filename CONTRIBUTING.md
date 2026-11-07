# Contributing to MetalRender

Thank you for your support in improving MetalRender. Your changes should be understandable, safe and verified on a Mac before you submit a PR (Pull Request). Please review this document before submitting any PR. 

## Before you start

- Check the README and existing documentation to understand the project and its supported environment
- Explain the problem it solves and any visible behavior changes
- **If you do NOT have a Mac, do not submit a pull request** unless you are making a change to documentation
- YOU MUST run a test
- There are no naming conventions, naming styles, code styles or any repo-specific styling requirements in MetalRender. You need not specify or follow any in your PR
- There are no restrictions on the use of AI to make a PR, as long as a human has reviewed the code and a test been run successfully

## Development and verification

- Use the Java version and dependencies specified by the project, run with `./gradlew runclient`.
- Run the game on a supported macOS and Apple GPU setup and exercise the changed behavior. A successful compile alone is not sufficien. a person must perform and confirm runtime test before the change is considered ready 
- Include the steps you followed and the outcome in your pull request. If a required test could not be run, state why and identify what still needs verification. DO NOT present untested behavior as tested

## Human review

- Every change must be read and reviewed by a person before it is merged. The reviewer should understand what the code does, check that it fits the project and look for unintended effects
- Contributors should read their own complete diff before submitting it. Remove unrelated edits and temporary files
- A passing build or test does not replace human review or runtime testing

## Safety and security

Changes must not introduce dangerous, deceptive, or unauthorised behavior. In particular:

- Do not add code that deletes, encrypts, corrupts, or uploads user files or data without a clear, necessary, user-visible feature and explicit user action
- Do not include code that adds telemetary of users' sensitive device information such as IP address, MAC address and other identifiable information
- Do not execute downloaded or user-provided content, run hidden commands, bypass operating-system security, or weaken permissions and security controls
- Do not include secrets, private keys, access tokens, or personal data in source, logs, test fixtures or commits. Make sure to tell your AI agent (should you be using one) to not include your personal details where possible. 

there shouldn't be any security issues to find, so don't even bother. 

## Code and documentation

- Keep changes as small and pratical as possible
- update documentation when behavior, setup, commands, configuration, or compatibility changes
- Do not claim support for hardware, game versions, or configurations that you have not verified
- When fixing issues, detail the issue first

## Pull requests

A pull request should include:

- A concise description of the change and the reason for it
- The relevant issue or discussion, if there is one
- Build and test steps, including the human-run in-game verification and its result
- Any limitations, compatibility effects, or risks reviewers should know about

You should respond to review feedback and update the pull request until the change is understood, safely verified and ready to merge.

---

Thank you for your continued support in MetalRender's development.  