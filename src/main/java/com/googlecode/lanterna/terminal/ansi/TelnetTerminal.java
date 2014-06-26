/*
 * This file is part of lanterna (http://code.google.com/p/lanterna/).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010-2014 Martin
 */
package com.googlecode.lanterna.terminal.ansi;

import com.googlecode.lanterna.input.KeyStroke;
import static com.googlecode.lanterna.terminal.ansi.TelnetProtocol.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * A good resource on telnet communication is http://www.tcpipguide.com/free/t_TelnetProtocol.htm
 * @author martin
 */
public class TelnetTerminal extends ANSITerminal {
    
    private final Socket socket;

    TelnetTerminal(Socket socket, Charset terminalCharset) throws IOException {
        this(socket, new TelnetClientIACFilterer(socket.getInputStream()), socket.getOutputStream(), terminalCharset);
    }
    
    //This weird construction is just so that we can access the input filter without changing the visibility in StreamBasedTerminal
    private TelnetTerminal(Socket socket, TelnetClientIACFilterer inputStream, OutputStream outputStream, Charset terminalCharset) throws IOException {
        super(inputStream, outputStream, terminalCharset);
        this.socket = socket;
        inputStream.setEventListener(new TelnetClientEventListener() {
            @Override
            public void onResize(int columns, int rows) {
                TelnetTerminal.this.onResized(columns, rows);
            }
        });
        setLineMode0();
        setEchoOff();
        setResizeNotificationOn();
    }
    
    private void setEchoOff() throws IOException {
        writeToTerminal(COMMAND_IAC, COMMAND_WILL, OPTION_ECHO);
        flush();
    }
    
    private void setLineMode0() throws IOException {
        writeToTerminal(
                COMMAND_IAC, COMMAND_DO, OPTION_LINEMODE,
                COMMAND_IAC, COMMAND_SUBNEGOTIATION, OPTION_LINEMODE, (byte)1, (byte)0, COMMAND_IAC, COMMAND_SUBNEGOTIATION_END);
        flush();
    }

    private void setResizeNotificationOn() throws IOException {
        writeToTerminal(
                COMMAND_IAC, COMMAND_DO, OPTION_NAWS);
        flush();
    }

    @Override
    public KeyStroke readInput() throws IOException {
        KeyStroke keyStroke = super.readInput();
        return keyStroke;
    }
    
    public void close() throws IOException {
        socket.close();
    }
    
    private static interface TelnetClientEventListener {
        void onResize(int columns, int rows);
    }
    
    private static class TelnetClientIACFilterer extends InputStream {
        private final InputStream inputStream;
        private final byte[] buffer;
        private final byte[] workingBuffer;
        private int bytesInBuffer;
        private TelnetClientEventListener eventListener;

        public TelnetClientIACFilterer(InputStream inputStream) {
            this.inputStream = inputStream;
            this.buffer = new byte[64 * 1024];
            this.workingBuffer = new byte[1024];
            this.bytesInBuffer = 0;
            this.eventListener = null;
        }

        public void setEventListener(TelnetClientEventListener eventListener) {
            this.eventListener = eventListener;
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException("TelnetClientIACFilterer doesn't support .read()");
        }

        @Override
        public void close() throws IOException {
            inputStream.close();
        }

        @Override
        public int available() throws IOException {
            int underlyingStreamAvailable = inputStream.available();
            if(underlyingStreamAvailable == 0 && bytesInBuffer == 0) {
                return 0;
            }
            else if(underlyingStreamAvailable == 0) {
                return bytesInBuffer;
            }
            else if(bytesInBuffer == buffer.length) {
                return bytesInBuffer;
            }
            fillBuffer();
            return bytesInBuffer;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if(inputStream.available() > 0) {
                fillBuffer();
            }
            if(bytesInBuffer == 0) {
                return -1;
            }
            int bytesToCopy = Math.min(len, bytesInBuffer);
            System.arraycopy(buffer, 0, b, off, bytesToCopy);
            System.arraycopy(buffer, bytesToCopy, buffer, 0, buffer.length - bytesToCopy);
            bytesInBuffer -= bytesToCopy;
            return bytesToCopy;
        }

        private void fillBuffer() throws IOException {
            int readBytes = inputStream.read(workingBuffer, 0, Math.min(workingBuffer.length, buffer.length - bytesInBuffer));
            if(readBytes == -1) {
                return;
            }
            for(int i = 0; i < readBytes; i++) {
                if(workingBuffer[i] == COMMAND_IAC) {
                    i++;
                    if(Arrays.asList(COMMAND_DO, COMMAND_DONT, COMMAND_WILL, COMMAND_WONT).contains(workingBuffer[i])) {
                        String call = CODE_TO_NAME.get(workingBuffer[i++]);
                        String operation = CODE_TO_NAME.get(workingBuffer[i]);
                        System.out.println("Got IAC " + call + " " + operation);
                        continue;
                    }
                    else if(workingBuffer[i] == COMMAND_SUBNEGOTIATION) {   //0xFA = SB = Subnegotiation
                        //Wait for IAC
                        String operation = CODE_TO_NAME.get(workingBuffer[++i]);
                        List<Byte> extraData = new ArrayList<Byte>();
                        while(workingBuffer[++i] != COMMAND_SUBNEGOTIATION_END) {
                            extraData.add(workingBuffer[i]);
                        }
                        System.out.print("Got IAC SB " + operation);
                        for(Byte data: extraData) {
                            System.out.print(" " + data);
                        }
                        System.out.println(" SE");
                        
                        if(operation.equals("NAWS") && eventListener != null) {
                            eventListener.onResize(convertTwoBytesToInt2(extraData.get(1), extraData.get(0)), convertTwoBytesToInt2(extraData.get(3), extraData.get(2)));
                        }
                    }
                    else {
                        System.err.println("Unknown Telnet command: " + workingBuffer[i]);
                    }
                }
                buffer[bytesInBuffer++] = workingBuffer[i];
            }
        }
    }
    
    private static int convertTwoBytesToInt2(byte b1, byte b2) {
        return (int) (( (b2 & 0xFF) << 8) | (b1 & 0xFF));
    }
}
