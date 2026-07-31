# upstream post-2.2.1 review

- incorporated upstream baseline: `7eeb00cc4da8c857f2a046c4c1c056610e2c0b16` (`2.2.1`)
- reviewed upstream head: `03678be2f565d1992bdebb51bdc062109b85bc57`
- new upstream commits: 4

## commits

1. `c42365c8bbf63fab80fa5ed667fd97ecad51fe48` — removes message-send confirmation and retry limits.
2. `3ef0875c2ea68aa0ac1b88e08891c815c20e76c0` — OpenRouter model catalog compatibility fix.
3. `820a2f590961f51aa0520b8dd419b869f3a1c5a8` — Android APK build/sign workflow.
4. `03678be2f565d1992bdebb51bdc062109b85bc57` — removes the dedicated `send_message` tool and routes message sending through unrestricted generic GUI execution without a second confirmation.

## blocking review finding

The two message-sending commits materially remove safety and behavior boundaries: dedicated tool contracts, sensitive-tool policy checks, confirmation flow, retry restrictions, and the specialized sender are deleted or bypassed. Applying these changes automatically would permit generic GUI execution to send messages without the existing confirmation boundary.

The OpenRouter compatibility fix and CI workflow can be evaluated separately. No app source changes, version change, APK build, merge, or release were performed in this review branch.
