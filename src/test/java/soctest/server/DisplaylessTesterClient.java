/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file Copyright (C) 2020 Jeremy D Monin <jeremy@nand.net>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * The maintainer of this program can be reached at jsettlers@nand.net
 **/

package soctest.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import soc.baseclient.SOCDisplaylessPlayerClient;
import soc.baseclient.ServerConnectInfo;
import soc.game.SOCGame;
import soc.game.SOCGameOption;
import soc.message.*;
import soc.server.genericServer.StringServerSocket;
import soc.util.SOCFeatureSet;
import soc.util.SOCGameList;
import soc.util.Version;

/**
 * Non-testing class: Robot utility client to help run the actual tests.
 * Works with {@link RecordingTesterServer}.
 * Debug Traffic flag is set, which makes unit test logs larger but is helpful when troubleshooting.
 * Unlike parent class, this client connects and authenticates as a "human" player, not a bot,
 * to see same messages a human would be shown.
 * @since 2.4.10
 */
public class DisplaylessTesterClient
    extends SOCDisplaylessPlayerClient
{

    /**
     * Track server's games and options like SOCPlayerClient does,
     * instead of ignoring them until joined like SOCRobotClient.
     *<P>
     * This field is null until {@link MessageHandler#handleGAMES(SOCGames, boolean) handleGAMES},
     *   {@link MessageHandler#handleGAMESWITHOPTIONS(SOCGamesWithOptions, boolean) handleGAMESWITHOPTIONS},
     *   {@link MessageHandler#handleNEWGAME(SOCNewGame, boolean) handleNEWGAME}
     *   or {@link MessageHandler#handleNEWGAMEWITHOPTIONS(SOCNewGameWithOptions, boolean) handleNEWGAMEWITHOPTIONS}
     *   is called.
     */
    protected SOCGameList serverGames = new SOCGameList();

    /** Treat inbound messages through this client's {@link SOCDisplaylessPlayerClient#run()} method. */
    protected Thread treaterThread;

    /**
     * Constructor for a displayless client which will connect to a local server.
     * Does not actually connect here: Call {@link #init()} when ready.
     */
    public DisplaylessTesterClient(final String stringport, final String nickname)
    {
        super(new ServerConnectInfo(stringport, null), false);

        this.nickname = nickname;
        debugTraffic = true;
    }

    /**
     * Initialize the displayless client; connect to server and send first messages
     * including our version, features from {@link #buildClientFeats()}, and {@link #rbclass}.
     * If fails to connect, sets {@link #ex} and prints it to {@link System#err}.
     * Based on {@link soc.robot.SOCRobotClient#init()}.
     *<P>
     * When done testing, caller should use {@link SOCDisplaylessPlayerClient#destroy()} to shut down.
     */
    public void init()
    {
        try
        {
            if (serverConnectInfo.stringSocketName == null)
            {
                s = new Socket(serverConnectInfo.hostname, serverConnectInfo.port);
                s.setSoTimeout(300000);
                in = new DataInputStream(s.getInputStream());
                out = new DataOutputStream(s.getOutputStream());
            }
            else
            {
                sLocal = StringServerSocket.connectTo(serverConnectInfo.stringSocketName);
            }
            connected = true;
            treaterThread = new Thread(this);
            treaterThread.setDaemon(true);
            treaterThread.start();

            put(new SOCVersion
                (Version.versionNumber(), Version.version(), Version.buildnum(),
                 buildClientFeats().getEncodedList(), "en_US").toCmd());
            put(new SOCAuthRequest
                (SOCAuthRequest.ROLE_GAME_PLAYER, nickname, "",
                 SOCAuthRequest.SCHEME_CLIENT_PLAINTEXT, "-").toCmd());
        }
        catch (Exception e)
        {
            ex = e;
            System.err.println("Could not connect to the server: " + ex);
        }
    }

    // from SOCRobotClient ; TODO combine common later?
    protected SOCFeatureSet buildClientFeats()
    {
        SOCFeatureSet feats = new SOCFeatureSet(false, false);
        feats.add(SOCFeatureSet.CLIENT_6_PLAYERS);
        feats.add(SOCFeatureSet.CLIENT_SEA_BOARD);
        feats.add(SOCFeatureSet.CLIENT_SCENARIO_VERSION, Version.versionNumber());

        return feats;
    }

    /**
     * To show successful connection, get the server's version.
     * Same format as {@link soc.util.Version#versionNumber()}.
     */
    public int getServerVersion()
    {
        return sLocalVersion;
    }

    // message handlers

    // TODO refactor common with SOCPlayerClient vs this and its displayless parent,
    // which currently don't share a parent client class with SOCPlayerClient

    @Override
    protected void handleGAMES(final SOCGames mes)
    {
        serverGames.addGames(mes.getGames(), Version.versionNumber());
    }

    @Override
    protected void handleGAMESWITHOPTIONS(final SOCGamesWithOptions mes)
    {
        serverGames.addGames(mes.getGameList(), Version.versionNumber());
    }

    @Override
    protected void handleNEWGAME(final SOCNewGame mes)
    {
        String gameName = mes.getGame();
        boolean canJoin = true;
        boolean hasUnjoinMarker = (gameName.charAt(0) == SOCGames.MARKER_THIS_GAME_UNJOINABLE);
        if (hasUnjoinMarker)
        {
            gameName = gameName.substring(1);
            canJoin = false;
        }
        serverGames.addGame(gameName, null, ! canJoin);
    }

    @Override
    protected void handleNEWGAMEWITHOPTIONS(final SOCNewGameWithOptions mes)
    {
        String gameName = mes.getGame();
        boolean canJoin = (mes.getMinVersion() <= Version.versionNumber());
        if (gameName.charAt(0) == SOCGames.MARKER_THIS_GAME_UNJOINABLE)
        {
            gameName = gameName.substring(1);
            canJoin = false;
        }
        serverGames.addGame(gameName, mes.getOptionsString(), ! canJoin);
    }

    @Override
    protected void handleJOINGAMEAUTH(SOCJoinGameAuth mes, final boolean isPractice)
    {
        gotPassword = true;

        String gameName = mes.getGame();

        Map<String, SOCGameOption> opts = serverGames.parseGameOptions(gameName);

        final int bh = mes.getBoardHeight(), bw = mes.getBoardWidth();
        if ((bh != 0) || (bw != 0))
        {
            // Encode board size to pass through game constructor
            if (opts == null)
                opts = new HashMap<String,SOCGameOption>();
            SOCGameOption opt = SOCGameOption.getOption("_BHW", true);
            opt.setIntValue((bh << 8) | bw);
            opts.put("_BHW", opt);
        }

        final SOCGame ga = new SOCGame(gameName, opts);
        ga.isPractice = isPractice;
        ga.serverVersion = (isPractice) ? sLocalVersion : sVersion;
        games.put(gameName, ga);
    }


}
