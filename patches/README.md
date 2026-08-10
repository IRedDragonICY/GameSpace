# Framework Cleanup Patches

If your ROM source tree contains legacy GameSpace framework services in `frameworks/base`, apply this patch to restore `frameworks/base` back to standard AOSP state:

```bash
cd frameworks/base
git apply ../../packages/apps/GameSpace/patches/0001-revert-legacy-framework-gamespace.patch
```

This patch removes:
- Custom AIDLs (`IGameSpaceService.aidl`, `IGameSpaceCallback.aidl`)
- Framework services (`GameSpaceService.java`, `GameStateDispatcher.java`)
- Framework models (`SidebarMode.java`)
- Service calls in `DisplayContent.java`, `ActivityTaskSupervisor.java`, `KeyguardController.java`, and `AxExtServiceFactory.java`
