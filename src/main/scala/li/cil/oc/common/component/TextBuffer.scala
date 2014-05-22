package li.cil.oc.common.component

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.relauncher.{SideOnly, Side}
import li.cil.oc.{api, Settings}
import li.cil.oc.api.component.TextBuffer.ColorDepth
import li.cil.oc.api.network._
import li.cil.oc.client.{PacketSender => ClientPacketSender, ComponentTracker => ClientComponentTracker}
import li.cil.oc.client.renderer.{MonospaceFontRenderer, TextBufferRenderCache}
import li.cil.oc.common.tileentity
import li.cil.oc.server.{PacketSender => ServerPacketSender, ComponentTracker => ServerComponentTracker, component}
import li.cil.oc.util
import li.cil.oc.util.ExtendedNBT._
import li.cil.oc.util.PackedColor
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.nbt.NBTTagCompound
import scala.collection.convert.WrapAsScala._

class TextBuffer(val owner: component.Container) extends ManagedComponent with api.component.TextBuffer {
  val node = api.Network.newNode(this, Visibility.Network).
    withComponent("screen").
    withConnector().
    create()

  private var maxResolution = Settings.screenResolutionsByTier(0)

  private var maxDepth = Settings.screenDepthsByTier(0)

  private var aspectRatio = (1.0, 1.0)

  private var powerConsumptionPerTick = Settings.get.screenCost

  // For client side only.
  private var isRendering = true

  private var isDisplaying = true

  private var hasPower = true

  private var relativeLitArea = -1.0

  var fullyLitCost = computeFullyLitCost()

  // This computes the energy cost (per tick) to keep the screen running if
  // every single "pixel" is lit. This cost increases with higher tiers as
  // their maximum resolution (pixel density) increases. For a basic screen
  // this is simply the configured cost.
  def computeFullyLitCost() = {
    val (w, h) = Settings.screenResolutionsByTier(0)
    val mw = getMaximumWidth
    val mh = getMaximumHeight
    powerConsumptionPerTick * (mw * mh) / (w * h)
  }

  val proxy =
    if (FMLCommonHandler.instance.getEffectiveSide.isClient) new TextBuffer.ClientProxy(this)
    else new TextBuffer.ServerProxy(this)

  val data = new util.TextBuffer(maxResolution, PackedColor.Depth.format(maxDepth))

  // ----------------------------------------------------------------------- //

  override val canUpdate = true

  override def update() {
    super.update()
    if (isDisplaying && owner.world.getWorldTime % Settings.get.tickFrequency == 0) {
      if (relativeLitArea < 0) {
        // The relative lit area is the number of pixels that are not blank
        // versus the number of pixels in the *current* resolution. This is
        // scaled to multi-block screens, since we only compute this for the
        // origin.
        val w = getWidth
        val h = getHeight
        relativeLitArea = (data.buffer, data.color).zipped.foldLeft(0) {
          case (acc, (line, colors)) => acc + (line, colors).zipped.foldLeft(0) {
            case (acc2, (char, color)) =>
              val bg = PackedColor.unpackBackground(color, data.format)
              val fg = PackedColor.unpackForeground(color, data.format)
              acc2 + (if (char == ' ') if (bg == 0) 0 else 1
              else if (char == 0x2588) if (fg == 0) 0 else 1
              else if (fg == 0 && bg == 0) 0 else 1)
          }
        } / (w * h).toDouble
      }
      if (node != null) {
        val hadPower = hasPower
        val neededPower = relativeLitArea * fullyLitCost * Settings.get.tickFrequency
        hasPower = node.tryChangeBuffer(-neededPower)
        if (hasPower != hadPower) {
          ServerPacketSender.sendTextBufferPowerChange(node.address, isDisplaying && hasPower, owner)
        }
      }
    }
  }

  // ----------------------------------------------------------------------- //

  @Callback(doc = """function():boolean -- Returns whether the screen is currently on.""")
  def isOn(computer: Context, args: Arguments): Array[AnyRef] = result(isDisplaying)

  @Callback(doc = """function():boolean -- Turns the screen on. Returns true if it was off.""")
  def turnOn(computer: Context, args: Arguments): Array[AnyRef] = {
    val oldPowerState = isDisplaying
    setPowerState(value = true)
    result(isDisplaying != oldPowerState, isDisplaying)
  }

  @Callback(doc = """function():boolean -- Turns off the screen. Returns true if it was on.""")
  def turnOff(computer: Context, args: Arguments): Array[AnyRef] = {
    val oldPowerState = isDisplaying
    setPowerState(value = false)
    result(isDisplaying != oldPowerState, isDisplaying)
  }

  @Callback(doc = """function():number, number -- The aspect ratio of the screen. For multi-block screens this is the number of blocks, horizontal and vertical.""")
  def getAspectRatio(context: Context, args: Arguments): Array[AnyRef] = {
    result(aspectRatio._1, aspectRatio._2)
  }

  // ----------------------------------------------------------------------- //

  override def setEnergyCostPerTick(value: Double) {
    powerConsumptionPerTick = value
    fullyLitCost = computeFullyLitCost()
  }

  override def getEnergyCostPerTick = powerConsumptionPerTick

  override def setPowerState(value: Boolean) {
    if (isDisplaying != value) {
      isDisplaying = value
      if (isDisplaying) {
        val neededPower = fullyLitCost * Settings.get.tickFrequency
        hasPower = node.changeBuffer(-neededPower) == 0
      }
      ServerPacketSender.sendTextBufferPowerChange(node.address, isDisplaying && hasPower, owner)
    }
  }

  override def getPowerState = isDisplaying

  override def setMaximumResolution(width: Int, height: Int) {
    if (width < 1) throw new IllegalArgumentException("width must be larger or equal to one")
    if (height < 1) throw new IllegalArgumentException("height must be larger or equal to one")
    maxResolution = (width, height)
    fullyLitCost = computeFullyLitCost()
  }

  override def getMaximumWidth = maxResolution._1

  override def getMaximumHeight = maxResolution._2

  override def setAspectRatio(width: Double, height: Double) = aspectRatio = (width, height)

  override def getAspectRatio = aspectRatio._1 / aspectRatio._2

  override def setResolution(w: Int, h: Int) = {
    val (mw, mh) = maxResolution
    if (w < 1 || h < 1 || w > mw || h > mw || h * w > mw * mh)
      throw new IllegalArgumentException("unsupported resolution")
    if (data.size = (w, h)) {
      if (node != null) {
        node.sendToReachable("computer.signal", "screen_resized", Int.box(w), Int.box(h))
      }
      proxy.onScreenResolutionChange(w, h)
      true
    }
    else false
  }

  override def getWidth = data.width

  override def getHeight = data.height

  override def setMaximumColorDepth(depth: ColorDepth) = maxDepth = depth

  override def getMaximumColorDepth = maxDepth

  override def setColorDepth(depth: ColorDepth) = {
    if (depth.ordinal > maxDepth.ordinal)
      throw new IllegalArgumentException("unsupported depth")
    if (data.format = PackedColor.Depth.format(depth)) {
      proxy.onScreenDepthChange(depth)
      true
    }
    else false
  }

  override def getColorDepth = data.format.depth

  override def setPaletteColor(index: Int, color: Int) = data.format match {
    case palette: PackedColor.MutablePaletteFormat =>
      palette(index) = color
      proxy.onScreenPaletteChange(index)
    case _ => throw new Exception("palette not available")
  }

  override def getPaletteColor(index: Int) = data.format match {
    case palette: PackedColor.MutablePaletteFormat => palette(index)
    case _ => throw new Exception("palette not available")
  }

  override def setForegroundColor(color: Int) = setForegroundColor(color, isFromPalette = false)

  override def setForegroundColor(color: Int, isFromPalette: Boolean) {
    val value = PackedColor.Color(color, isFromPalette)
    if (data.foreground != value) {
      data.foreground = value
      proxy.onScreenColorChange()
    }
  }

  override def getForegroundColor = data.foreground.value

  override def isForegroundFromPalette = data.foreground.isPalette

  override def setBackgroundColor(color: Int) = setBackgroundColor(color, isFromPalette = false)

  override def setBackgroundColor(color: Int, isFromPalette: Boolean) {
    val value = PackedColor.Color(color, isFromPalette)
    if (data.background != value) {
      data.background = value
      proxy.onScreenColorChange()
    }
  }

  override def getBackgroundColor = data.background.value

  override def isBackgroundFromPalette = data.background.isPalette

  def copy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int) =
    if (data.copy(col, row, w, h, tx, ty))
      proxy.onScreenCopy(col, row, w, h, tx, ty)

  def fill(col: Int, row: Int, w: Int, h: Int, c: Char) =
    if (data.fill(col, row, w, h, c))
      proxy.onScreenFill(col, row, w, h, c)

  def set(col: Int, row: Int, s: String, vertical: Boolean) = if (col < data.width && (col >= 0 || -col < s.length)) {
    // Make sure the string isn't longer than it needs to be, in particular to
    // avoid sending too much data to our clients.
    val (x, y, truncated) =
      if (vertical) {
        if (row < 0) (col, 0, s.substring(-row))
        else (col, row, s.substring(0, math.min(s.length, data.height - row)))
      }
      else {
        if (col < 0) (0, row, s.substring(-col))
        else (col, row, s.substring(0, math.min(s.length, data.width - col)))
      }
    if (data.set(x, y, truncated, vertical))
      proxy.onScreenSet(x, row, truncated, vertical)
  }

  def get(col: Int, row: Int) = data.get(col, row)

  override def getForegroundColor(column: Int, row: Int) =
    PackedColor.unpackForeground(data.color(row)(column), data.format)

  override def isForegroundFromPalette(column: Int, row: Int) = data.format match {
    case palette: PackedColor.PaletteFormat => palette.isFromPalette(PackedColor.extractForeground(data.color(row)(column)))
    case _ => false
  }

  override def getBackgroundColor(column: Int, row: Int) =
    PackedColor.unpackBackground(data.color(row)(column), data.format)

  override def isBackgroundFromPalette(column: Int, row: Int) = data.format match {
    case palette: PackedColor.PaletteFormat => palette.isFromPalette(PackedColor.extractBackground(data.color(row)(column)))
    case _ => false
  }

  @SideOnly(Side.CLIENT)
  override def renderText() = {
    if (relativeLitArea != 0) {
      proxy.render()
    }
  }

  @SideOnly(Side.CLIENT)
  override def renderWidth = MonospaceFontRenderer.fontWidth * data.width

  @SideOnly(Side.CLIENT)
  override def renderHeight = MonospaceFontRenderer.fontHeight * data.height

  @SideOnly(Side.CLIENT)
  override def setRenderingEnabled(enabled: Boolean) = isRendering = enabled

  @SideOnly(Side.CLIENT)
  override def isRenderingEnabled = isRendering

  override def keyDown(character: Char, code: Int, player: EntityPlayer) =
    proxy.keyDown(character, code, player)

  override def keyUp(character: Char, code: Int, player: EntityPlayer) =
    proxy.keyUp(character, code, player)

  override def clipboard(value: String, player: EntityPlayer) =
    proxy.clipboard(value, player)

  override def mouseDown(x: Int, y: Int, button: Int, player: EntityPlayer) =
    proxy.mouseDown(x, y, button, player)

  override def mouseDrag(x: Int, y: Int, button: Int, player: EntityPlayer) =
    proxy.mouseDrag(x, y, button, player)

  override def mouseUp(x: Int, y: Int, button: Int, player: EntityPlayer) =
    proxy.mouseUp(x, y, button, player)

  override def mouseScroll(x: Int, y: Int, delta: Int, player: EntityPlayer) =
    proxy.mouseScroll(x, y, delta, player)

  // ----------------------------------------------------------------------- //

  override def onConnect(node: Node) {
    super.onConnect(node)
    if (node == this.node) {
      ServerComponentTracker.add(node.address, this)
    }
  }

  override def onDisconnect(node: Node) {
    super.onDisconnect(node)
    if (node == this.node) {
      ServerComponentTracker.remove(node.address)
    }
  }

  // ----------------------------------------------------------------------- //

  override def load(nbt: NBTTagCompound) = {
    super.load(nbt)
    data.load(nbt.getCompoundTag("buffer"))
    if (FMLCommonHandler.instance.getEffectiveSide.isClient) {
      proxy.nodeAddress = nbt.getCompoundTag("node").getString("address")
      ClientComponentTracker.add(proxy.nodeAddress, this)
    }

    if (nbt.hasKey(Settings.namespace + "isOn")) {
      isDisplaying = nbt.getBoolean(Settings.namespace + "isOn")
    }
    if (nbt.hasKey(Settings.namespace + "hasPower")) {
      hasPower = nbt.getBoolean(Settings.namespace + "hasPower")
    }
  }

  // Null check for Waila (and other mods that may call this client side).
  override def save(nbt: NBTTagCompound) = if (node != null) {
    super.save(nbt)
    // Happy thread synchronization hack! Here's the problem: GPUs allow direct
    // calls for modifying screens to give a more responsive experience. This
    // causes the following problem: when saving, if the screen is saved first,
    // then the executor runs in parallel and changes the screen *before* the
    // server thread begins saving that computer, the saved computer will think
    // it changed the screen, although the saved screen wasn't. To avoid that we
    // wait for all computers the screen is connected to to finish their current
    // execution and pausing them (which will make them resume in the next tick
    // when their update() runs).
    if (node.network != null) {
      for (node <- node.reachableNodes) node.host match {
        case host: tileentity.traits.Computer if !host.isPaused =>
          host.pause(0.1)
        case _ =>
      }
    }

    nbt.setNewCompoundTag("buffer", data.save)
    nbt.setBoolean(Settings.namespace + "isOn", isDisplaying)
    nbt.setBoolean(Settings.namespace + "hasPower", hasPower)
  }
}

object TextBuffer {

  abstract class Proxy {
    var dirty = false

    var nodeAddress = ""

    def render() {}

    def onScreenColorChange()

    def onScreenCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int)

    def onScreenDepthChange(depth: ColorDepth)

    def onScreenFill(col: Int, row: Int, w: Int, h: Int, c: Char)

    def onScreenPaletteChange(index: Int)

    def onScreenResolutionChange(w: Int, h: Int)

    def onScreenSet(col: Int, row: Int, s: String, vertical: Boolean)

    def keyDown(character: Char, code: Int, player: EntityPlayer)

    def keyUp(character: Char, code: Int, player: EntityPlayer)

    def clipboard(value: String, player: EntityPlayer)

    def mouseDown(x: Int, y: Int, button: Int, player: EntityPlayer)

    def mouseDrag(x: Int, y: Int, button: Int, player: EntityPlayer)

    def mouseUp(x: Int, y: Int, button: Int, player: EntityPlayer)

    def mouseScroll(x: Int, y: Int, delta: Int, player: EntityPlayer)
  }

  class ClientProxy(val owner: TextBuffer) extends Proxy {
    override def render() = TextBufferRenderCache.render(owner)

    override def onScreenColorChange() = dirty = true

    override def onScreenCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int) = dirty = true

    override def onScreenDepthChange(depth: ColorDepth) = dirty = true

    override def onScreenFill(col: Int, row: Int, w: Int, h: Int, c: Char) = dirty = true

    override def onScreenPaletteChange(index: Int) = dirty = true

    override def onScreenResolutionChange(w: Int, h: Int) = dirty = true

    override def onScreenSet(col: Int, row: Int, s: String, vertical: Boolean) = dirty = true

    override def keyDown(character: Char, code: Int, player: EntityPlayer) =
      ClientPacketSender.sendKeyDown(nodeAddress, character, code)

    override def keyUp(character: Char, code: Int, player: EntityPlayer) =
      ClientPacketSender.sendKeyUp(nodeAddress, character, code)

    override def clipboard(value: String, player: EntityPlayer) =
      ClientPacketSender.sendClipboard(nodeAddress, value)

    override def mouseDown(x: Int, y: Int, button: Int, player: EntityPlayer) =
      ClientPacketSender.sendMouseClick(nodeAddress, x, y, drag = false, button)

    override def mouseDrag(x: Int, y: Int, button: Int, player: EntityPlayer) =
      ClientPacketSender.sendMouseClick(nodeAddress, x, y, drag = true, button)

    override def mouseUp(x: Int, y: Int, button: Int, player: EntityPlayer) =
      ClientPacketSender.sendMouseUp(nodeAddress, x, y, button)

    override def mouseScroll(x: Int, y: Int, delta: Int, player: EntityPlayer) =
      ClientPacketSender.sendMouseScroll(nodeAddress, x, y, delta)
  }

  class ServerProxy(val buffer: TextBuffer) extends Proxy {
    override def onScreenColorChange() {
      buffer.owner.markChanged()
      ServerPacketSender.sendTextBufferColorChange(buffer.node.address, buffer.data.foreground, buffer.data.background, buffer.owner)
    }

    override def onScreenCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int) {
      buffer.relativeLitArea = -1
      buffer.owner.markChanged()
      ServerPacketSender.sendTextBufferCopy(buffer.node.address, col, row, w, h, tx, ty, buffer.owner)
    }

    override def onScreenDepthChange(depth: ColorDepth) {
      buffer.owner.markChanged()
      ServerPacketSender.sendTextBufferDepthChange(buffer.node.address, depth, buffer.owner)
    }

    override def onScreenFill(col: Int, row: Int, w: Int, h: Int, c: Char) {
      buffer.relativeLitArea = -1
      buffer.owner.markChanged()
      ServerPacketSender.sendTextBufferFill(buffer.node.address, col, row, w, h, c, buffer.owner)
    }

    override def onScreenPaletteChange(index: Int) {
      buffer.owner.markChanged()
      ServerPacketSender.sendTextBufferPaletteChange(buffer.node.address, index, buffer.getPaletteColor(index), buffer.owner)
    }

    override def onScreenResolutionChange(w: Int, h: Int) {
      buffer.relativeLitArea = -1
      buffer.owner.markChanged()
      ServerPacketSender.sendTextBufferResolutionChange(buffer.node.address, w, h, buffer.owner)
    }

    override def onScreenSet(col: Int, row: Int, s: String, vertical: Boolean) {
      buffer.relativeLitArea = -1
      buffer.owner.markChanged()
      ServerPacketSender.sendTextBufferSet(buffer.node.address, col, row, s, vertical, buffer.owner)
    }

    override def keyDown(character: Char, code: Int, player: EntityPlayer) {
      buffer.node.sendToVisible("keyboard.keyDown", player, Char.box(character), Int.box(code))
    }

    override def keyUp(character: Char, code: Int, player: EntityPlayer) {
      buffer.node.sendToVisible("keyboard.keyUp", player, Char.box(character), Int.box(code))
    }

    override def clipboard(value: String, player: EntityPlayer) {
      buffer.node.sendToVisible("keyboard.clipboard", player, value)
    }

    override def mouseDown(x: Int, y: Int, button: Int, player: EntityPlayer) {
      if (Settings.get.inputUsername) buffer.node.sendToReachable("computer.checked_signal", player, "touch", Int.box(x), Int.box(y), Int.box(button), player.getCommandSenderName)
      else buffer.node.sendToReachable("computer.checked_signal", player, "touch", Int.box(x), Int.box(y), Int.box(button))
    }

    override def mouseDrag(x: Int, y: Int, button: Int, player: EntityPlayer) {
      if (Settings.get.inputUsername) buffer.node.sendToReachable("computer.checked_signal", player, "drag", Int.box(x), Int.box(y), Int.box(button), player.getCommandSenderName)
      else buffer.node.sendToReachable("computer.checked_signal", player, "drag", Int.box(x), Int.box(y), Int.box(button))
    }

    override def mouseUp(x: Int, y: Int, button: Int, player: EntityPlayer) {
      if (Settings.get.inputUsername) buffer.node.sendToReachable("computer.checked_signal", player, "drop", Int.box(x), Int.box(y), Int.box(button), player.getCommandSenderName)
      else buffer.node.sendToReachable("computer.checked_signal", player, "drop", Int.box(x), Int.box(y), Int.box(button))
    }

    override def mouseScroll(x: Int, y: Int, delta: Int, player: EntityPlayer) {
      if (Settings.get.inputUsername) buffer.node.sendToReachable("computer.checked_signal", player, "scroll", Int.box(x), Int.box(y), Int.box(delta), player.getCommandSenderName)
      else buffer.node.sendToReachable("computer.checked_signal", player, "scroll", Int.box(x), Int.box(y), Int.box(delta))
    }
  }

}