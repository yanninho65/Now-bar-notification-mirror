import os,re,sys
res,pkg,out=sys.argv[1],sys.argv[2],sys.argv[3]
types={}
for root,dirs,files in os.walk(res):
    d=os.path.basename(root).split('-')[0]
    for f in files:
        name=os.path.splitext(f)[0]
        if d in ('layout','drawable','mipmap','xml','color'):
            types.setdefault(d,set()).add(name)
        txt=open(os.path.join(root,f),encoding='utf-8',errors='ignore').read()
        for m in re.finditer(r'@\+id/(\w+)',txt): types.setdefault('id',set()).add(m.group(1))
        if d=='values':
            for m in re.finditer(r'<(string|color|dimen|style|bool|integer)\s+name="([\w.]+)"',txt):
                t=m.group(1); types.setdefault(t,set()).add(m.group(2).replace('.','_'))
lines=[f"package {pkg}","object R {"]
for t,names in types.items():
    lines.append(f"  object {t} {{")
    for i,n in enumerate(sorted(names)): lines.append(f"    const val {n} = {i+1}")
    lines.append("  }")
lines.append("}")
open(out,'w').write("\n".join(lines))
