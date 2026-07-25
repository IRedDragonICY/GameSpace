# GameSpace

IRedDragonICY's GameSpace is a heavily modified fork of [chaldeaprjkt's GameSpace](https://github.com/chaldeaprjkt/packages_apps_GameSpace). It serves as an advanced alternative to Google's proprietary implementation of the Game Dashboard with the goal of providing a powerful user-interface for the [Android Game Mode API](https://developer.android.com/games/gamemode/gamemode-api) and hardware-level GPU tuning.

This repository has been upgraded with the following features:
- **Jetpack Compose Migration:** Modernized and decoupled UI architecture.
- **GPU Hardware Interception:** Added toggles for MSAA (2x/4x), Anisotropic Filtering (AF), Texture Filtering Quality, and Adreno Frame Motion Engine (AFME).
- **Advanced Diagnostics:** Improved Thermal Profiles and Touch Latency / Sample Rate benchmarking tools.

### Required Patches
*Coming Soon*

## Credits & Acknowledgements
Special thanks to the open-source community that made this possible:
- **[Chaldeaprjkt (packages_apps_GameSpace)](https://github.com/chaldeaprjkt/packages_apps_GameSpace):** For the original implementation of GameSpace.
- **[crDroid Android Project](https://github.com/crdroidandroid):** For their UI components and inspirations.

## License

This work is licensed under [Apache 2.0 License](LICENSE.md).
