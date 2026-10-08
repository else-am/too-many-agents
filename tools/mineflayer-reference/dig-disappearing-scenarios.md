# Disappearing dig target — preauthored

Use the isolated reference world: empty-hand survival player, reachable stone at
60,-60,3, no nearby drops. Begin one dig and retain its promise handlers. After
public targetDigBlock is observed and three physics ticks pass with the target
still stone, the fixture callback removes exactly that block without drops.
The callback is setup coordination, not an agent capability; native execution
must use an independent observed running-action gate instead of exposing it.

Pinned digging.js completes on a new-air update. Require one completed event
with new air and cleared target/face, void promise resolution, no aborted event,
no item gain, and no event/action replay for160ticks. Independently verify air,
empty inventory and no drops in the server. Retain exact source, timings and
warnings. This proves disappearance handling, not that the bot broke the block
or collected anything. Native comparison remains separate and unrun.
