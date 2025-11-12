import { useRef, useEffect, useState } from 'react'

/**
 * Shared Whiteboard Component
 * Real-time collaborative drawing canvas
 */
function WhiteboardCanvas({ whiteboard }) {
  const canvasRef = useRef(null)
  const [isDrawing, setIsDrawing] = useState(false)
  const [lastPos, setLastPos] = useState({ x: 0, y: 0 })
  const [currentColor, setCurrentColor] = useState('#000000')
  const [thickness, setThickness] = useState(2)
  const [eraserSize, setEraserSize] = useState(20)
  const [tool, setTool] = useState('pen') // 'pen' or 'eraser'

  const colors = ['#000000', '#FF0000', '#00FF00', '#0000FF', '#FFFF00', '#FF00FF', '#00FFFF', '#FFFFFF']

  // Initialize canvas
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'

    // Set canvas size
    canvas.width = canvas.offsetWidth
    canvas.height = canvas.offsetHeight

    // Fill with white background
    ctx.fillStyle = '#FFFFFF'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
  }, [])

  // Redraw all commands when they change
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    
    // Clear canvas
    ctx.fillStyle = '#FFFFFF'
    ctx.fillRect(0, 0, canvas.width, canvas.height)

    // Set line properties for smooth drawing
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.globalCompositeOperation = 'source-over'

    // First pass: Draw all non-eraser strokes
    whiteboard.commands.forEach(cmd => {
      if (cmd.type === 'draw' && cmd.color !== '#FFFFFF') {
        ctx.strokeStyle = cmd.color
        ctx.lineWidth = cmd.thickness
        ctx.beginPath()
        ctx.moveTo(cmd.x1, cmd.y1)
        ctx.lineTo(cmd.x2, cmd.y2)
        ctx.stroke()
      }
    })

    // Second pass: Apply eraser strokes using destination-out
    ctx.globalCompositeOperation = 'destination-out'
    whiteboard.commands.forEach(cmd => {
      if (cmd.type === 'draw' && cmd.color === '#FFFFFF') {
        ctx.strokeStyle = 'rgba(0,0,0,1)'
        ctx.lineWidth = cmd.thickness
        ctx.beginPath()
        ctx.moveTo(cmd.x1, cmd.y1)
        ctx.lineTo(cmd.x2, cmd.y2)
        ctx.stroke()
      }
    })
    
    // Reset composite operation
    ctx.globalCompositeOperation = 'source-over'
  }, [whiteboard.commands])

  const getMousePos = (e) => {
    const canvas = canvasRef.current
    const rect = canvas.getBoundingClientRect()
    return {
      x: e.clientX - rect.left,
      y: e.clientY - rect.top
    }
  }

  const handleMouseDown = (e) => {
    setIsDrawing(true)
    const pos = getMousePos(e)
    setLastPos(pos)
  }

  const handleMouseMove = (e) => {
    if (!isDrawing) return

    const pos = getMousePos(e)
    const color = tool === 'eraser' ? '#FFFFFF' : currentColor
    const lineThickness = tool === 'eraser' ? eraserSize : thickness

    // Send drawing command to server
    whiteboard.sendDrawCommand(
      lastPos.x,
      lastPos.y,
      pos.x,
      pos.y,
      color,
      lineThickness
    )

    setLastPos(pos)
  }

  const handleMouseUp = () => {
    setIsDrawing(false)
  }

  const handleClear = () => {
    whiteboard.sendClearCommand()
  }

  const handleClose = () => {
    if (window.confirm('Close whiteboard? All drawings will be lost.')) {
      whiteboard.closeSession()
    }
  }

  if (!whiteboard.isOpen) {
    return null
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4">
      <div className="flex h-full max-h-[90vh] w-full max-w-6xl flex-col rounded-2xl border border-white/10 bg-slate-900 shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-white/10 px-6 py-4">
          <div>
            <h2 className="text-xl font-bold text-white">
              🎨 Shared Whiteboard
            </h2>
            <p className="text-sm text-slate-400">
              Drawing with <span className="font-semibold text-brand-400">{whiteboard.otherUser}</span>
            </p>
          </div>
          <button
            onClick={handleClose}
            className="rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-red-500"
          >
            ✕ Close
          </button>
        </div>

        {/* Toolbar */}
        <div className="flex flex-wrap items-center gap-4 border-b border-white/10 bg-slate-800/50 px-6 py-3">
          {/* Tool Selection */}
          <div className="flex gap-2">
            <button
              onClick={() => setTool('pen')}
              className={`rounded px-3 py-2 text-sm font-semibold transition ${
                tool === 'pen'
                  ? 'bg-brand-500 text-white'
                  : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
              }`}
            >
              ✏️ Pen
            </button>
            <button
              onClick={() => setTool('eraser')}
              className={`rounded px-3 py-2 text-sm font-semibold transition ${
                tool === 'eraser'
                  ? 'bg-brand-500 text-white'
                  : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
              }`}
            >
              🧹 Eraser
            </button>
          </div>

          {/* Color Picker */}
          {tool === 'pen' && (
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-slate-400">Color:</span>
              <div className="flex gap-1">
                {colors.map(color => (
                  <button
                    key={color}
                    onClick={() => setCurrentColor(color)}
                    className={`h-8 w-8 rounded border-2 transition ${
                      currentColor === color
                        ? 'scale-110 border-white'
                        : 'border-transparent hover:scale-105'
                    }`}
                    style={{ backgroundColor: color }}
                    title={color}
                  />
                ))}
              </div>
            </div>
          )}

          {/* Thickness */}
          {tool === 'pen' && (
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-slate-400">Size:</span>
              <input
                type="range"
                min="1"
                max="20"
                value={thickness}
                onChange={(e) => setThickness(parseInt(e.target.value))}
                className="h-2 w-24 cursor-pointer rounded-lg bg-slate-700 accent-brand-500"
              />
              <span className="text-xs font-semibold text-slate-300">{thickness}px</span>
            </div>
          )}

          {/* Eraser Size */}
          {tool === 'eraser' && (
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-slate-400">Eraser Size:</span>
              <input
                type="range"
                min="5"
                max="50"
                value={eraserSize}
                onChange={(e) => setEraserSize(parseInt(e.target.value))}
                className="h-2 w-24 cursor-pointer rounded-lg bg-slate-700 accent-brand-500"
              />
              <span className="text-xs font-semibold text-slate-300">{eraserSize}px</span>
            </div>
          )}

          <div className="flex-1" />

          {/* Clear Button */}
          <button
            onClick={handleClear}
            className="rounded bg-orange-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-orange-500"
          >
            🗑️ Clear All
          </button>
        </div>

        {/* Canvas */}
        <div className="relative flex-1 overflow-hidden bg-white p-4">
          <canvas
            ref={canvasRef}
            onMouseDown={handleMouseDown}
            onMouseMove={handleMouseMove}
            onMouseUp={handleMouseUp}
            onMouseLeave={handleMouseUp}
            className="h-full w-full cursor-crosshair rounded border-2 border-slate-200"
            style={{ touchAction: 'none' }}
          />
          
          {/* Status Indicator */}
          <div className="absolute bottom-6 left-6 rounded-lg bg-slate-900/90 px-3 py-2 text-xs text-white">
            <div className="flex items-center gap-2">
              <div className="h-2 w-2 animate-pulse rounded-full bg-green-400" />
              <span>
                {tool === 'eraser' ? '🧹 Erasing' : '✏️ Drawing'} • 
                {' '}{whiteboard.commands.filter(cmd => cmd.color !== '#FFFFFF').length} strokes
              </span>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="border-t border-white/10 bg-slate-800/50 px-6 py-3 text-center text-xs text-slate-400">
          💡 Tip: Click and drag to draw. Your partner will see your drawings in real-time!
        </div>
      </div>
    </div>
  )
}

export default WhiteboardCanvas
