#!/usr/bin/env python3
"""
Minimal OpenRSC mock server for testing client rendering.
Responds to the opcode-19 server config request with a valid 90-field packet
so the client can get past getServerConfig() and render the login screen.
"""
import socket
import sys

RSA_EXPONENT = "65537"
RSA_MODULUS  = ("7112866275597968156550007489163685737528267584779959617759901583041"
                "864787078477876689003422509099353805015177703670715380710894892460"
                "637136582066351659813")

def s(text):
    """Encodes a string field (readString reads until \\n)."""
    return text.encode('ascii') + b'\x0a'

def b(val):
    """Encodes a single-byte field (getUnsignedByte / getByte)."""
    return bytes([val & 0xFF])

def build_config_packet():
    body = b''
    body += s("OpenRSC")              # 1  serverName
    body += s("OpenRSC")              # 2  serverNameWelcome
    body += b(18)                     # 3  playerLevelLimit
    body += b(0)                      # 4  spawnAuctionNpcs
    body += b(0)                      # 5  spawnIronManNpcs
    body += b(1)                      # 6  showFloatingNametags
    body += b(0)                      # 7  wantClans
    body += b(0)                      # 8  wantKillFeed
    body += b(1)                      # 9  fogToggle
    body += b(1)                      # 10 groundItemToggle
    body += b(0)                      # 11 autoMessageSwitchToggle
    body += b(0)                      # 12 batchProgression
    body += b(1)                      # 13 sideMenuToggle
    body += b(1)                      # 14 inventoryCountToggle
    body += b(1)                      # 15 zoomViewToggle
    body += b(1)                      # 16 menuCombatStyleToggle
    body += b(1)                      # 17 fightmodeSelectorToggle
    body += b(1)                      # 18 experienceCounterToggle
    body += b(1)                      # 19 experienceDropsToggle
    body += b(0)                      # 20 itemsOnDeathMenu
    body += b(1)                      # 21 showRoofToggle
    body += b(1)                      # 22 wantHideIp
    body += b(1)                      # 23 wantRemember
    body += b(1)                      # 24 wantGlobalChat
    body += b(1)                      # 25 wantSkillMenus
    body += b(1)                      # 26 wantQuestMenus
    body += b(1)                      # 27 wantExperienceElixirs
    body += b(1)                      # 28 wantKeyboardShortcuts
    body += b(1)                      # 29 wantCustomBanks
    body += b(0)                      # 30 wantBankPins
    body += b(0)                      # 31 wantBankNotes
    body += b(0)                      # 32 wantCertDeposit
    body += b(0)                      # 33 customFiremaking
    body += b(1)                      # 34 wantDropX
    body += b(1)                      # 35 wantExpInfo
    body += b(0)                      # 36 wantWoodcuttingGuild
    body += b(0)                      # 37 wantDecanting
    body += b(0)                      # 38 wantCertsToBank
    body += b(0)                      # 39 wantCustomRankDisplay
    body += b(0)                      # 40 wantRightClickBank
    body += b(0)                      # 41 wantFixedOverheadChat
    body += s("Welcome to OpenRSC!")  # 42 welcomeText
    body += b(0)                      # 43 wantMembers
    body += b(0)                      # 44 displayLogoSprite
    body += s("0")                    # 45 logoSpriteID
    body += b(30)                     # 46 getFPS
    body += b(0)                      # 47 wantEmail
    body += b(0)                      # 48 wantRegistrationLimit
    body += b(1)                      # 49 allowResize
    body += b(0)                      # 50 lenientContactDetails
    body += b(1)                      # 51 wantFatigue
    body += b(0)                      # 52 wantCustomSprites
    body += b(1)                      # 53 wantPlayerCommands
    body += b(1)                      # 54 wantPets
    body += b(2)                      # 55 maxWalkingSpeed
    body += b(0)                      # 56 showUnidentifiedHerbNames
    body += b(1)                      # 57 wantQuestStartedIndicator
    body += b(0)                      # 58 fishingSpotsDepletable
    body += b(1)                      # 59 improvedItemObjectNames
    body += b(1)                      # 60 wantRunecraft
    body += b(0)                      # 61 wantCustomLandscape
    body += b(1)                      # 62 wantEquipmentTab
    body += b(1)                      # 63 wantBankPresets
    body += b(1)                      # 64 wantParties
    body += b(0)                      # 65 miningRocksExtended
    body += b(2)                      # 66 movePerFrame       (getByte)
    body += b(0)                      # 67 wantLeftclickWebs  (getByte)
    body += b(0)                      # 68 npcKillCounters    (getByte)
    body += b(0)                      # 69 wantCustomUI
    body += b(0)                      # 70 wantGlobalFriend
    body += b(1)                      # 71 characterCreationMode
    body += b(1)                      # 72 skillingExpRate
    body += b(0)                      # 73 wantHarvesting
    body += b(0)                      # 74 hideLoginBox
    body += b(0)                      # 75 globalFriendChat
    body += b(1)                      # 76 wantRightClickTrade
    body += b(1)                      # 77 featuresSleep
    body += b(0)                      # 78 wantExtendedCatsBehavior
    body += b(0)                      # 79 wantCertAsNotes
    body += b(0)                      # 80 wantOpenPkPoints
    body += b(0)                      # 81 openPkPointsToGpRatio
    body += b(0)                      # 82 wantOpenPkPresets
    body += b(1)                      # 83 showUndergroundFlickerToggle
    body += b(0)                      # 84 disableMinimapRotation
    body += b(0)                      # 85 allowBeardedLadies
    body += b(0)                      # 86 prideMonth
    body += s(RSA_EXPONENT)           # 87 RSA_EXPONENT (readString)
    body += s(RSA_MODULUS)            # 88 RSA_MODULUS  (readString)
    body += b(0)                      # 89 groundItemNames
    body += b(0)                      # 90 wantNatureRuneProtection

    # Header: [ack=0][len=0][opcode=19] + body
    return bytes([0, 0, 19]) + body

def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 43594
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('127.0.0.1', port))
    srv.listen(5)
    print(f"[mock] Listening on localhost:{port} — waiting for client")
    sys.stdout.flush()

    while True:
        conn, addr = srv.accept()
        print(f"[mock] Client connected from {addr}")
        sys.stdout.flush()
        try:
            # Consume client's opcode-19 request (don't need to parse it)
            conn.recv(4096)
            packet = build_config_packet()
            conn.sendall(packet)
            print(f"[mock] Sent config packet ({len(packet)} bytes)")
            sys.stdout.flush()
            # Keep the connection open so the client doesn't get EOF
            while True:
                data = conn.recv(4096)
                if not data:
                    break
        except Exception as e:
            print(f"[mock] Connection error: {e}")
        finally:
            conn.close()
            print("[mock] Client disconnected")
            sys.stdout.flush()

if __name__ == '__main__':
    main()
