"""Génère les icônes de l'appli (Pillow) : couche avant de l'icône adaptative + icône classique.

Dessin : croissant de lune ambré et petite étoile, sur fond nuit (couleurs de la page).
Usage : python3 tools/make_icons.py res
"""
import os
import sys
from PIL import Image, ImageChops, ImageDraw

AMBRE, ENCRE, NUIT = (224, 163, 92, 255), (234, 217, 194, 255), (21, 17, 15, 255)
SS = 4  # suréchantillonnage pour l'anticrénelage


def symbole(taille, u):
    """Calque transparent taille x taille ; u = pixels par unité (symbole dessiné sur 36 x 36 unités, centré)."""
    c = taille / 2
    P = lambda x, y: (c + (x - 18) * u, c + (y - 18) * u)
    lune = Image.new('L', (taille, taille), 0)
    ImageDraw.Draw(lune).ellipse([*P(3, 3), *P(31, 31)], fill=255)
    creux = Image.new('L', (taille, taille), 0)
    ImageDraw.Draw(creux).ellipse([*P(11, -1), *P(37, 25)], fill=255)
    masque = ImageChops.subtract(lune, creux)
    calque = Image.new('RGBA', (taille, taille), (0, 0, 0, 0))
    calque.paste(Image.new('RGBA', (taille, taille), AMBRE), (0, 0), masque)
    d = ImageDraw.Draw(calque)
    cx, cy, r, f = 27, 9, 5.2, 1.1     # étoile à quatre branches
    d.polygon([P(cx, cy - r), P(cx + f, cy - f), P(cx + r, cy), P(cx + f, cy + f),
               P(cx, cy + r), P(cx - f, cy + f), P(cx - r, cy), P(cx - f, cy - f)], fill=ENCRE)
    return calque


def foreground(px):
    """Couche avant 108 dp (px = taille en pixels), symbole contenu dans la zone sûre (66 dp)."""
    s = px * SS
    return symbole(s, s / 108 * 52 / 36).resize((px, px), Image.LANCZOS)


def legacy(px):
    """Icône classique 48 dp : carré arrondi nuit + symbole."""
    s = px * SS
    big = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle([s * 0.04, s * 0.04, s * 0.96, s * 0.96], radius=s * 0.22, fill=NUIT)
    big.alpha_composite(symbole(s, s / 36 * 0.62))
    return big.resize((px, px), Image.LANCZOS)


def main(res):
    for name, k in [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]:
        d = os.path.join(res, 'mipmap-' + name)
        os.makedirs(d, exist_ok=True)
        foreground(int(108 * k)).save(os.path.join(d, 'ic_launcher_fg.png'))
        legacy(int(48 * k)).save(os.path.join(d, 'ic_launcher.png'))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'res')
