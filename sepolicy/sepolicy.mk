# Copyright (C) 2026 IRedDragonICY
# SPDX-License-Identifier: Apache-2.0
#
# Include this file in your device's BoardConfig.mk:
#   include packages/apps/GameSpace/sepolicy/sepolicy.mk

SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += packages/apps/GameSpace/sepolicy/public
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += packages/apps/GameSpace/sepolicy/private
BOARD_VENDOR_SEPOLICY_DIRS += packages/apps/GameSpace/sepolicy/vendor
