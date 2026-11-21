import struct, gzip
# minimal NBT reader/writer (big-endian, java)
def _rs(b,i):
    n=struct.unpack('>H',b[i:i+2])[0]; return b[i+2:i+2+n].decode('utf-8'), i+2+n
def _rp(t,b,i):
    if t==1: return b[i], i+1
    if t==2: return struct.unpack('>h',b[i:i+2])[0], i+2
    if t==3: return struct.unpack('>i',b[i:i+4])[0], i+4
    if t==4: return struct.unpack('>q',b[i:i+8])[0], i+8
    if t==5: return struct.unpack('>f',b[i:i+4])[0], i+4
    if t==6: return struct.unpack('>d',b[i:i+8])[0], i+8
    if t==7: n=struct.unpack('>i',b[i:i+4])[0]; return ('B',list(b[i+4:i+4+n])), i+4+n
    if t==8: return _rs(b,i)
    if t==9:
        et=b[i]; n=struct.unpack('>i',b[i+1:i+5])[0]; i+=5; out=[]
        for _ in range(n): v,i=_rp(et,b,i); out.append(v)
        return ('L',et,out), i
    if t==10:
        d={}
        while True:
            tt=b[i]; i+=1
            if tt==0: break
            k,i=_rs(b,i); v,i=_rp(tt,b,i); d[k]=(tt,v)
        return d,i
    if t==11: n=struct.unpack('>i',b[i:i+4])[0]; return ('I',list(struct.unpack('>%di'%n,b[i+4:i+4+4*n]))), i+4+4*n
    if t==12: n=struct.unpack('>i',b[i:i+4])[0]; return ('J',list(struct.unpack('>%dq'%n,b[i+4:i+4+8*n]))), i+4+8*n
    raise ValueError(t)
def read(path):
    b=gzip.open(path).read(); assert b[0]==10; name,i=_rs(b,1); d,i=_rp(10,b,i); return d
def _ws(s): e=s.encode('utf-8'); return struct.pack('>H',len(e))+e
def _wp(t,v):
    if t==1: return struct.pack('>b',v)
    if t==2: return struct.pack('>h',v)
    if t==3: return struct.pack('>i',v)
    if t==4: return struct.pack('>q',v)
    if t==5: return struct.pack('>f',v)
    if t==6: return struct.pack('>d',v)
    if t==7: return struct.pack('>i',len(v[1]))+bytes(v[1])
    if t==8: return _ws(v)
    if t==9: _,et,items=v; return bytes([et])+struct.pack('>i',len(items))+b''.join(_wp(et,x) for x in items)
    if t==10: return b''.join(bytes([tt])+_ws(k)+_wp(tt,vv) for k,(tt,vv) in v.items())+b'\x00'
    if t==11: return struct.pack('>i',len(v[1]))+struct.pack('>%di'%len(v[1]),*v[1])
    if t==12: return struct.pack('>i',len(v[1]))+struct.pack('>%dq'%len(v[1]),*v[1])
    raise ValueError(t)
def write(path,d):
    with gzip.open(path,'wb') as f: f.write(b'\x0a'+_ws('')+_wp(10,d))
# helpers
def S(s): return (8,s)
def I(i): return (3,i)
def C(d): return (10,d)
def L(et,items): return (9,('L',et,items))
def IA(a): return (11,('I',a))
