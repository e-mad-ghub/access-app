from PIL import Image, ImageDraw

# Settings
size = 1024
scale = size / 100.0
padding = 0.65  # The scaleX/scaleY 0.65 from the XML for unzoomed look
offset = 17.5   # Center the scaled logo

def t(val):
    return (val * padding + offset) * scale

# PURE BLACK BACKGROUND
img = Image.new("RGBA", (size, size), (0, 0, 0, 255))
draw = ImageDraw.Draw(img)

mint_green = (0, 191, 165, 255)
deep_teal = (0, 96, 100, 255)

# 1. QR Brackets (Adjusted path for unzoomed alignment)
brackets = [
    [ (20,20), (45,20), (45,28), (25,28), (25,48), (20,48) ],
    [ (63,20), (88,20), (88,48), (83,48), (83,28), (63,28) ],
    [ (20,60), (25,60), (25,80), (45,80), (45,88), (20,88) ],
    [ (83,60), (88,60), (88,88), (63,88), (63,80), (83,80) ]
]

for poly in brackets:
    draw.polygon([ (t(x), t(y)) for x, y in poly ], fill=mint_green)

# 2. Checkmark
checkmark = [ (35,55), (48,68), (85,31), (92,38), (48,78), (28,62) ]
draw.polygon([ (t(x), t(y)) for x, y in checkmark ], fill=mint_green)

# 3. Person Head
head_bbox = [t(54-10), t(38-10), t(54+10), t(38+10)]
draw.ellipse(head_bbox, fill=deep_teal)

# 4. Person Body
body = [ (34,68), (74,68), (74,62), (64,52), (54,52), (44,52), (34,62) ]
draw.polygon([ (t(x), t(y)) for x, y in body ], fill=deep_teal)

img.save("easypass_icon_black.png")
print("Logo saved as easypass_icon_black.png")
