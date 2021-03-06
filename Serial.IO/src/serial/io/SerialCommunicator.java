package serial.io;

import gnu.io.CommPortIdentifier;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;

import gnu.io.UnsupportedCommOperationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TooManyListenersException;

public class SerialCommunicator
  implements SerialPortEventListener
{
  private SerialIOCallbacks parent;
  private boolean verbose = false;

  private SerialPort serialPort = null;

  // Serial input and output
  private InputStream  input  = null;
  private OutputStream output = null;

  private boolean isConnected = false;

  private final static int TIMEOUT = 2_000;

  private final static int DEFAULT_BAUD_RATE     = 9600; 
  private final static int DEFAULT_FLOW_CTRL_IN  = SerialPort.FLOWCONTROL_NONE; 
  private final static int DEFAULT_FLOW_CTRL_OUT = SerialPort.FLOWCONTROL_NONE;
  private final static int DEFAULT_DATABITS      = SerialPort.DATABITS_8;
  private final static int DEFAULT_STOIP_BITS    = SerialPort.STOPBITS_1;
  private final static int DEFAULT_PARITY        = SerialPort.PARITY_NONE;

  public SerialCommunicator(SerialIOCallbacks caller)
  {
    this.parent = caller;
  }

  public Map<String, CommPortIdentifier> getPortList()
  {
    Map<String, CommPortIdentifier> portMap = new HashMap<String, CommPortIdentifier>();
    Enumeration ports = CommPortIdentifier.getPortIdentifiers();
    while (ports.hasMoreElements())
    {
      CommPortIdentifier curPort = (CommPortIdentifier) ports.nextElement();
      // Serial ports only
      if (curPort.getPortType() == CommPortIdentifier.PORT_SERIAL)
      {
        portMap.put(curPort.getName(), curPort);
      }
    }
    return portMap;
  }
  public void connect(CommPortIdentifier port) throws PortInUseException, Exception
  {
    connect(port, "");
  }
  public void connect(CommPortIdentifier port, String userPortName) throws PortInUseException, Exception
  {
    connect(port, userPortName, DEFAULT_BAUD_RATE);
  }
  public void connect(CommPortIdentifier port, String userPortName, int br) throws PortInUseException, Exception, UnsupportedCommOperationException
  {
    connect(port, userPortName, br, DEFAULT_DATABITS, DEFAULT_STOIP_BITS, DEFAULT_PARITY, DEFAULT_FLOW_CTRL_IN, DEFAULT_FLOW_CTRL_OUT);
  }
  public void connect(CommPortIdentifier port, String userPortName, int br, int db, int sb, int par, int fIn, int fOut) throws PortInUseException, Exception, UnsupportedCommOperationException
  {
    try
    {
      serialPort = (SerialPort) port.open(userPortName, TIMEOUT);
      serialPort.setSerialPortParams(br, db, sb, par);
      try
      {
        serialPort.setFlowControlMode(fIn | fOut);
      }
      catch (UnsupportedCommOperationException e)
      {
        throw e;
      }
      setConnected(true);
      this.parent.connected(true);
    }
    catch (PortInUseException e)
    {
      throw e;
    }
    catch (Exception e)
    {
      throw e;
    }
  }

  public boolean initIOStream() throws IOException
  {
    boolean success = false;
    try
    {
      input  = serialPort.getInputStream();
      output = serialPort.getOutputStream();
      success = true;
    }
    catch (IOException e)
    {
      throw e;
    }
    return success;
  }

  public void initListener() throws TooManyListenersException
  {
    try
    {
      serialPort.addEventListener(this);
      serialPort.notifyOnDataAvailable(true);
    }
    catch (TooManyListenersException e)
    {
      throw e;
    }
  }

  public void disconnect() throws IOException
  {
    try
    {
      serialPort.removeEventListener();
      serialPort.close();
      if (input != null)
        input.close();
      if (output != null)
        output.close();
      setConnected(false);
      this.parent.connected(false);

    }
    catch (IOException e)
    {
      throw e;
    }
  }

  public final boolean isConnected()
  {
    return isConnected;
  }

  public void setConnected(boolean b)
  {
    this.isConnected = b;
  }

  public void setVerbose(boolean b)
  {
    this.verbose = b;  
  }
  
  @Override
  public void serialEvent(SerialPortEvent evt)
  {
    if (verbose)
      System.out.println("serialEvent");
    if (evt.getEventType() == SerialPortEvent.DATA_AVAILABLE)
    {
      try
      {
        byte b = (byte) input.read();
        this.parent.onSerialData(b);
      }
      catch (IOException e)
      {
        throw new RuntimeException(e);
      }
    }
  }
  
  public void writeData(String s) throws IOException
  {
    writeData(s.getBytes());
  }
  
  public void writeData(byte[] ba) throws IOException
  {
    for (byte b: ba)
      writeData(b);
  }

  public void writeData(byte b) throws IOException
  {
    if (verbose)
      System.out.println("Written to serial port => character [0x" + Integer.toHexString(b) + "]");

    try
    {
      output.write(b & 0xFF);
      try { Thread.sleep(10L); } catch (InterruptedException ie) {} // QUESTION: ???
//    output.flush();
    }
    catch (IOException e)
    {
      throw e;
    }
  }
  
  public void flushSerial() throws IOException
  {
    output.flush();
  }
}
