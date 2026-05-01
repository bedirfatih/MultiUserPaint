package multiuserpaint.server;

import multiuserpaint.common.*;
import multiuserpaint.server.handlers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes incoming messages to the appropriate handler based on FSM state.
 * Runs on the NIO thread only.
 */
public class MessageDispatcher {
    private static final Logger LOG = Logger.getLogger(MessageDispatcher.class.getName());

    private final LoginHandler loginHandler;
    private final FileHandler fileHandler;
    private final DrawHandler drawHandler;
    private final ClipboardHandler clipboardHandler;

    public MessageDispatcher(LoginHandler loginHandler, FileHandler fileHandler,
                             DrawHandler drawHandler, ClipboardHandler clipboardHandler) {
        this.loginHandler = loginHandler;
        this.fileHandler = fileHandler;
        this.drawHandler = drawHandler;
        this.clipboardHandler = clipboardHandler;
    }

    /**
     * Dispatches one message for the given session.
     * @return true if the session should remain open, false if it should be closed.
     */
    public boolean dispatch(ClientSession session, Message msg) {
        try {
            switch (session.getState()) {
                case HANDSHAKE:
                    return dispatchHandshake(session, msg);
                case ACTIVE:
                    return dispatchActive(session, msg);
                case SAVING:
                    // Ignore messages during disk write (except ping)
                    if (msg.getType() == MessageType.PING) {
                        enqueue(session, MessageEncoder.encodePong());
                        session.updatePingTime();
                    }
                    return true;
                default:
                    return false;
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Dispatch error for " + session, e);
            session.transitionTo(FSMState.ERROR);
            return false;
        }
    }

    private boolean dispatchHandshake(ClientSession session, Message msg) throws IOException {
        if (msg.getType() != MessageType.LOGIN_REQ) {
            enqueue(session, MessageEncoder.encodeError("Login required first"));
            session.transitionTo(FSMState.ERROR);
            return false;
        }
        loginHandler.handle(session, msg);
        return true;
    }

    private boolean dispatchActive(ClientSession session, Message msg) throws IOException {
        switch (msg.getType()) {
            // File operations
            case FILE_CREATE_REQ:
                fileHandler.handleCreate(session, msg);
                break;
            case FILE_OPEN_REQ:
                fileHandler.handleOpen(session, msg);
                break;
            case FILE_SAVE_REQ:
                session.transitionTo(FSMState.SAVING);
                fileHandler.handleSave(session, msg);
                break;
            case FILE_DELETE_REQ:
                fileHandler.handleDelete(session, msg);
                break;
            case FILE_LIST_REQ:
                fileHandler.handleList(session, msg);
                break;

            // Drawing
            case DRAW_EVENT:
                drawHandler.handle(session, msg);
                break;

            // Clipboard
            case CLIPBOARD_COPY:
            case CLIPBOARD_CUT:
                clipboardHandler.handleCopyOrCut(session, msg);
                break;
            case CLIPBOARD_PASTE_REQ:
                clipboardHandler.handlePasteReq(session, msg);
                break;

            // User list
            case USER_LIST_REQ:
                // Handled inline — small enough
                enqueue(session, MessageEncoder.encodeUserListResp(
                    // registry access via handler would be cleaner; direct for simplicity
                    new java.util.ArrayList<>()
                ));
                break;

            // Keepalive
            case PING:
                enqueue(session, MessageEncoder.encodePong());
                session.updatePingTime();
                break;

            default:
                LOG.warning("Unhandled message type in ACTIVE state: " + msg.getType()
                    + " from " + session.getUsername());
                break;
        }
        return true;
    }

    private void enqueue(ClientSession session, byte[] data) {
        session.writeQueue.offer(ByteBuffer.wrap(data));
    }
}
