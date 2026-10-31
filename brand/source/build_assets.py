from pathlib import Path
import json,shutil,subprocess,math
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
R=Path(__file__).resolve().parents[1]
for d in ['logos','icons/ios/AppIcon.appiconset','icons/android/res/drawable','icons/android/res/mipmap-anydpi-v26','icons/android/res/mipmap-anydpi-v33','icons/web','tokens','social','fonts','preview','source','qa']: (R/d).mkdir(parents=True,exist_ok=True)
font=TTFont(str(R/'fonts/NotoSans-Bold.ttf')); gs=font.getGlyphSet(); cmap=font.getBestCmap(); up=font['head'].unitsPerEm
# Fonts and original license are bundled in fonts/; preserve them.

N='#123B46';T='#087F8C';A='#71E2CC';I='#152E35';BG='#F5FAF9';D='#0C2027'
def word(x,y,size,color,text='Vezuna'):
 out=[]; cur=0
 for c in text:
  g=gs[cmap[ord(c)]]; p=SVGPathPen(gs);g.draw(p)
  out.append(f'<path d="{p.getCommands()}" transform="translate({x+cur*size/up},{y}) scale({size/up}, {-size/up})" fill="{color}"/>');cur+=g.width
 return ''.join(out)
def mark(c1=N,c2=T,c3=T):return f'<g fill="none" stroke-width="14" stroke-linecap="round"><path d="M24 25 C28 47 38 65 50 77" stroke="{c1}"/><path d="M76 25 C72 47 62 65 50 77" stroke="{c2}"/></g><circle cx="50" cy="29" r="7" fill="{c3}"/>'
def svg(w,h,body):return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">{body}</svg>'
def save(path,txt): (R/path).write_text(txt)
def render(path,w=None):
 src=R/path; dst=src.with_suffix('.png');cmd=['inkscape',str(src),'--export-type=png',f'--export-filename={dst}']
 if w:cmd+=['--export-width='+str(w)]
 subprocess.run(cmd,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True);return dst
for name,cols in {'color':(N,T,T),'white':('#FFFFFF',)*3,'ink':(I,)*3,'dark':('#FFFFFF',A,A)}.items():
 save('logos/mark-'+name+'.svg',svg(100,100,mark(*cols)))
 save('logos/lockup-'+name+'.svg',svg(410,110,f'<g transform="translate(0 5)">{mark(*cols)}</g>'+word(116,77,67,cols[0])))
 render('logos/mark-'+name+'.svg',512);render('logos/lockup-'+name+'.svg',1640)
# square, opaque app artwork, no baked corner mask
for name,bg,cols in [('default',N,('#FFFFFF',A,A)),('dark',D,('#FFFFFF',A,A)),('tinted','#FFFFFF',('#111111',)*3)]:
 body=f'<rect width="1024" height="1024" fill="{bg}"/><g transform="translate(122 122) scale(7.8)">{mark(*cols)}</g>'
 save(f'icons/ios/AppIcon.appiconset/icon-{name}.svg',svg(1024,1024,body));render(f'icons/ios/AppIcon.appiconset/icon-{name}.svg')
 from PIL import Image
 p=R/f'icons/ios/AppIcon.appiconset/icon-{name}.png';Image.open(p).convert('RGB').save(p)
items=[{'filename':'icon-default.png','idiom':'universal','platform':'ios','size':'1024x1024'},{'filename':'icon-dark.png','idiom':'universal','platform':'ios','size':'1024x1024','appearances':[{'appearance':'luminosity','value':'dark'}]},{'filename':'icon-tinted.png','idiom':'universal','platform':'ios','size':'1024x1024','appearances':[{'appearance':'luminosity','value':'tinted'}]}]
save('icons/ios/AppIcon.appiconset/Contents.json',json.dumps({'images':items,'info':{'author':'xcode','version':1}},indent=2))
# Android art transformed .66 plus21=> mark bounds32..76 entirely safe
fg=f'<g transform="translate(21 21) scale(.66)">{mark("#FFFFFF",A,A)}</g>'
save('icons/android/foreground.svg',svg(108,108,fg));save('icons/android/monochrome.svg',svg(108,108,f'<g transform="translate(21 21) scale(.66)">{mark("#000000","#000000","#000000")}</g>'))
save('icons/android/background.svg',svg(108,108,f'<rect width="108" height="108" fill="{N}"/>'))
for f in ['foreground','monochrome','background']:render('icons/android/'+f+'.svg',432)
for name,cols in [('ic_vezuna_foreground',('#FFFFFF',A,A)),('ic_vezuna_monochrome',('#000000',)*3)]:
 xml=f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"><group android:scaleX="0.66" android:scaleY="0.66" android:translateX="21" android:translateY="21"><path android:fillColor="#00000000" android:pathData="M24,25 C28,47 38,65 50,77" android:strokeColor="{cols[0]}" android:strokeWidth="14" android:strokeLineCap="round"/><path android:fillColor="#00000000" android:pathData="M76,25 C72,47 62,65 50,77" android:strokeColor="{cols[1]}" android:strokeWidth="14" android:strokeLineCap="round"/><path android:pathData="M43,29 a7,7 0,1 0,14 0 a7,7 0,1 0,-14 0" android:fillColor="{cols[2]}"/></group></vector>'''
 save('icons/android/res/drawable/'+name+'.xml',xml)
save('icons/android/res/drawable/ic_vezuna_background.xml',f'<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="{N}"/></shape>')
for version,mono in [('v26',''),('v33','<monochrome android:drawable="@drawable/ic_vezuna_monochrome"/>')]:save(f'icons/android/res/mipmap-anydpi-{version}/ic_launcher.xml',f'<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_vezuna_background"/><foreground android:drawable="@drawable/ic_vezuna_foreground"/>{mono}</adaptive-icon>')
from PIL import Image
master=Image.open(R/'icons/ios/AppIcon.appiconset/icon-default.png')
master.resize((512,512),Image.Resampling.LANCZOS).save(R/'icons/android/play-store-512.png')
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
 p=R/f'icons/android/res/mipmap-{density}';p.mkdir(exist_ok=True);master.resize((size,size),Image.Resampling.LANCZOS).save(p/'ic_launcher.png')
for size in [16,32,48,180,192,512]:master.resize((size,size),Image.Resampling.LANCZOS).save(R/f'icons/web/icon-{size}.png')
master.resize((256,256)).save(R/'icons/web/favicon.ico',sizes=[(16,16),(32,32),(48,48)])
save('icons/web/manifest.fragment.json',json.dumps({'name':'Vezuna','short_name':'Vezuna','theme_color':N,'background_color':BG,'icons':[{'src':'icon-192.png','sizes':'192x192','type':'image/png','purpose':'any'},{'src':'icon-512.png','sizes':'512x512','type':'image/png','purpose':'any'}]},indent=2))
# tokens all semantic, intentional light/dark contrasts
light={'background':BG,'surface':'#FFFFFF','surfaceSubtle':'#E7F3F1','text':I,'textMuted':'#526C74','border':'#8CA7AD','primary':T,'onPrimary':'#FFFFFF','accent':A,'onAccent':N,'focus':'#086A78','success':'#176B50','warning':'#805500','danger':'#B3263B','info':'#245DA8'}
dark={'background':D,'surface':'#14313A','surfaceSubtle':'#1A3E48','text':'#E7F5F2','textMuted':'#ADC7CE','border':'#668992','primary':A,'onPrimary':D,'accent':'#35B7B8','onAccent':D,'focus':A,'success':'#87DEB4','warning':'#F5CB73','danger':'#FF9AAA','info':'#9DC4FF'}
tokens={'brand':{'name':'Vezuna','navy':N,'teal':T,'aqua':A},'color':{'light':light,'dark':dark},'font':{'family':'Noto Sans, system-ui, sans-serif','weights':{'regular':400,'bold':700},'sizes':{'caption':12,'label':14,'body':16,'title':24,'hero':40},'lineHeight':{'body':1.5,'heading':1.2}},'space':{'xs':4,'sm':8,'md':16,'lg':24,'xl':32,'xxl':48},'radius':{'control':12,'card':20,'pill':999},'motion':{'fast':120,'normal':200,'reduced':0},'touchTarget':{'iosPt':44,'androidDp':48,'webCssPx':48}}
save('tokens/tokens.json',json.dumps(tokens,indent=2));save('tokens/tokens.ts','export const vezunaTokens = '+json.dumps(tokens,indent=2)+' as const;\n')
css='/* Vezuna v1.0: load Noto Sans locally. Respect existing framework themes. */\n'
for selector,colors in [(':root',light),('[data-theme="dark"]',dark)]:
 css+=selector+' {\n'+''.join(f'  --vz-{k}: {v};\n' for k,v in colors.items())+'}\n'
css+=':root { --vz-font: "Noto Sans", system-ui, sans-serif; --vz-radius-control: 12px; --vz-radius-card: 20px; --vz-space: 4px; }\n@media (prefers-reduced-motion: reduce) { :root { --vz-motion-duration: 0ms; } }\n'
save('tokens/tokens.css',css)
def luminance(h):
 vals=[int(h[i:i+2],16)/255 for i in (1,3,5)];vals=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in vals];return sum(a*b for a,b in zip(vals,[.2126,.7152,.0722]))
def ratio(a,b):a,b=sorted([luminance(a),luminance(b)]);return (b+.05)/(a+.05)
checks=[]
for mode,c in [('light',light),('dark',dark)]:
 for fg,bg in [('text','background'),('text','surface'),('textMuted','surface'),('onPrimary','primary'),('onAccent','accent'),('danger','surface'),('warning','surface'),('success','surface'),('info','surface')]:
  r=ratio(c[fg],c[bg]);checks.append({'theme':mode,'foreground':fg,'background':bg,'ratio':round(r,2),'AA_normal_text':r>=4.5})
assert all(c['AA_normal_text'] for c in checks),checks
save('qa/contrast.json',json.dumps(checks,indent=2))
# social exact-copy minimal backgrounds, outlined wordmark, no personal health data
for name,w,h in [('og-1200x630',1200,630),('github-1280x640',1280,640),('feature-1024x500',1024,500)]:
 body=f'<rect width="{w}" height="{h}" fill="{N}"/><circle cx="{w-20}" cy="{h+30}" r="{h*.65}" fill="#194C57"/><g transform="translate({w*.08} {h*.2}) scale(2.1)">{mark("#FFFFFF",A,A)}</g>'+word(w*.29,h*.49,w*.105,'#FFFFFF')+f'<text x="{w*.30}" y="{h*.62}" fill="#D8F4EE" font-family="Noto Sans" font-size="{w*.026}">Your health, in one place.</text>'
 save('social/'+name+'.svg',svg(w,h,body));render('social/'+name+'.svg')
# overview board
body=f'<rect width="1600" height="1600" fill="{BG}"/><rect width="1600" height="650" fill="{N}"/>'
body+=f'<text x="100" y="90" fill="{A}" font-family="Noto Sans" font-size="22" letter-spacing="4">VEZUNA / BRAND SYSTEM / 01</text>'
body+=f'<g transform="translate(95 180) scale(3.3)">{mark("#FFFFFF",A,A)}</g>'+word(470,410,165,'#FFFFFF')
body+='<text x="480" y="485" fill="#D8F4EE" font-family="Noto Sans" font-size="32">Your health, in one place.</text>'
body+=f'<text x="100" y="735" fill="{N}" font-family="Noto Sans" font-size="32" font-weight="bold">One identity. Every language.</text>'
for j,(label,col) in enumerate([('Deep teal',N),('Active teal',T),('Aqua',A),('Canvas',BG),('Ink',I)]):
 x=100+j*290;body+=f'<rect x="{x}" y="790" width="250" height="150" rx="20" fill="{col}" stroke="#C4D9D7"/><text x="{x}" y="980" fill="{I}" font-family="Noto Sans" font-size="22">{label}</text><text x="{x}" y="1015" fill="#526C74" font-family="Noto Sans" font-size="20">{col}</text>'
for j,(label,bg,cols) in enumerate([('Light', '#FFFFFF',(N,T,T)),('Dark',D,('#FFFFFF',A,A)),('Monochrome','#E7F3F1',(I,I,I))]):
 x=100+j*500;body+=f'<rect x="{x}" y="1080" width="440" height="300" rx="24" fill="{bg}"/><g transform="translate({x+135} 1100) scale(1.7)">{mark(*cols)}</g><text x="{x+30}" y="1345" fill="{cols[0]}" font-family="Noto Sans" font-size="22">{label}</text>'
body+=f'<text x="100" y="1470" fill="{I}" font-family="Noto Sans" font-size="28">Noto Sans · Accessible color pairs · Editable vector masters</text><text x="100" y="1520" fill="#526C74" font-family="Noto Sans" font-size="22">Identity kit v1.0 / Initial identity proposal; trademark clearance pending.</text>'
save('preview/brand-overview.svg',svg(1600,1600,body));render('preview/brand-overview.svg')
# source copies for reproducibility
# This source is retained alongside the assets.
print('Generated',len(list(R.rglob('*'))),'paths. Contrast checks',checks)
