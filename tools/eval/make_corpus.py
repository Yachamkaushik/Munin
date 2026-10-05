"""Builds the evaluation corpus and queries (deterministic, seed 2026).

Outputs (committed):  tools/eval/data/manifest.json  - 300 synthetic documents with their text and ground truth
                      tools/eval/data/queries.json   - 60 queries (11 intents x 5 styles + 5 negatives)
Images are rendered from the manifest by render_images.swift (not committed).

Every document is synthetic. 11 "anchor" documents are hand-written and each is the single correct answer for one
query intent; the other 289 are template-generated fillers of the SAME types (other hostels, other flights, other
bills, ...), so a query has plenty of confusable near-misses. Anchor-specific words are excluded from fillers, and the
script asserts that, so each query has exactly one correct document.
"""
import json, random, pathlib, collections

OUT = pathlib.Path(__file__).parent / "data"
R = random.Random(2026)

MONTH_EN = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]
MONTH_HI = ["जनवरी","फरवरी","मार्च","अप्रैल","मई","जून","जुलाई","अगस्त","सितंबर","अक्टूबर","नवंबर","दिसंबर"]
MONTH_TE = ["జనవరి","ఫిబ్రవరి","మార్చి","ఏప్రిల్","మే","జూన్","జూలై","ఆగస్టు","సెప్టెంబర్","అక్టోబర్","నవంబర్","డిసెంబర్"]

def grp(n):  # Indian digit grouping
    s = str(int(n)); 
    if len(s) <= 3: return s
    h, t = s[:-3], s[-3:]
    parts = []
    while len(h) > 2: parts.insert(0, h[-2:]); h = h[:-2]
    if h: parts.insert(0, h)
    return ",".join(parts) + "," + t

def date(lang, y, m, d):
    mon = {"en": MONTH_EN, "hi": MONTH_HI, "te": MONTH_TE}[lang][m-1]
    return f"{d} {mon} {y}"

def rdate(lo=6, hi=11):  # a random date in 2026
    return 2026, R.randint(lo, hi), R.randint(1, 28)

def iso(y, m, d): return f"{y}-{m:02d}-{d:02d}"
def phone(): 
    n = str(R.choice("6789")) + "".join(R.choice("0123456789") for _ in range(9))
    return n[:5] + " " + n[5:], "+91" + n

EN_NAMES = ["Ananya Sharma","Rahul Verma","Priya Nair","Suresh Babu","Divya Menon","Arjun Rao","Neha Gupta","Vikram Singh","Sneha Patil","Manoj Kumar",
            "Lakshmi Prasad","Rohit Jain","Meghana Iyer","Harish Chandra","Pooja Desai","Sandeep Yadav","Kavya Shetty","Imran Khan","Swathi Reddy","Karthik Reddy"]
HI_NAMES = ["आरव शर्मा","अनन्या वर्मा","रोहन गुप्ता","सृष्टि सिंह","विवेक यादव","पूजा मिश्रा","अमित तिवारी","नेहा जोशी"]
TE_NAMES = ["అనిల్ కుమార్","సుమలత రెడ్డి","రాజేష్ బాబు","లావణ్య రావు","వెంకటేష్ గౌడ్","శ్రావ్య నాయుడు","మహేష్ చౌదరి","భవాని ప్రసాద్"]

docs = []
def add(type_, lang, title_lines, facts=None, extra=None, anchor=None, upi=None):
    docs.append({"id": f"d{len(docs)+1:03d}", "type": type_, "lang": lang, "lines": title_lines, "facts": facts or {}, "anchor": anchor, "upi": upi, **(extra or {})})

# ---------------------------------------------------------------- anchors (one per query intent) ----------------------
add("fee_receipt", "en", [("Sai Vidya Hostel", "b"), "Fee Receipt", "Student: Karthik Reddy", "Room: B-214", "Amount paid: Rs 45,000", "Paid on: 12 Sep 2026", "Receipt No: SV-20931"],
    {"AMOUNT": "45000", "DATE": "2026-09-12"}, anchor="A1")
add("utility_bill", "en", [("Southern Power Distribution", "b"), "Electricity Bill", "Consumer No: 2210045", "Billing month: September 2026", "Amount due: Rs 1,842", "Due date: 15 Oct 2026"],
    {"AMOUNT": "1842", "DATE": "2026-10-15"}, anchor="A2")
add("ticket", "en", [("IndiGo 6E 5214", "b"), "E-ticket", "Hyderabad (HYD) to Visakhapatnam (VTZ)", "Date: 21 Oct 2026", "Departure 07:35", "PNR: QX7M2K"],
    {"DATE": "2026-10-21"}, anchor="A3")
add("utility_bill", "en", [("Metro Water Board", "b"), "Water Bill", "Consumer: 88123", "Amount due: Rs 460", "Due date: 3 Nov 2026"],
    {"AMOUNT": "460", "DATE": "2026-11-03"}, anchor="A4")
add("notes", "en", [("Organic Chemistry - Lecture 7", "b"), "SN1 and SN2 reaction mechanisms", "Carbocation stability order", "Nucleophile strength and solvent effects", "Revision: Markovnikov's rule"], anchor="A5")
add("appointment", "en", [("Apollo Clinic", "b"), "Appointment card", "Dr. Meera Rao, Cardiology", "Address: Road No 36, Jubilee Hills", "Hyderabad 500033", "Phone: 98480 12345", "Appointment: 24 Sep 2026, 10:30 AM"],
    {"PHONE": "+919848012345", "DATE": "2026-09-24", "ADDRESS": "Road No 36, Jubilee Hills, Hyderabad 500033"}, anchor="A6")
# A7 is a UPI payment, added below with the other payments (needs the layout renderer)
add("timetable", "en", [("Semester Exam Timetable", "b"), "B.Tech III Year", "2 Dec 2026  Operating Systems", "4 Dec 2026  Computer Networks", "7 Dec 2026  Database Systems", "9 Dec 2026  Software Engineering"],
    {"DATE": "2026-12-02"}, anchor="A8")
add("fee_receipt", "hi", [("सरस्वती विद्या मंदिर", "b"), "शुल्क रसीद", "छात्र: आरव शर्मा", "जमा राशि: ₹12,500", "दिनांक: 18 अगस्त 2026"],
    {"AMOUNT": "12500", "DATE": "2026-08-18"}, anchor="A9")
add("notes", "hi", [("इतिहास नोट्स - मुगल साम्राज्य", "b"), "अकबर की नीतियाँ", "सुलह-ए-कुल और धार्मिक सहिष्णुता", "मनसबदारी व्यवस्था", "राजस्व प्रणाली"], anchor="A10")
add("fee_receipt", "te", [("శ్రీ చైతన్య జూనియర్ కాలేజీ", "b"), "ఫీజు రసీదు", "విద్యార్థి: అనిల్ కుమార్", "చెల్లించిన మొత్తం: రూ. 28,000", "తేదీ: 5 ఆగస్టు 2026"],
    {"AMOUNT": "28000", "DATE": "2026-08-05"}, anchor="A11")

# ---------------------------------------------------------------- fillers ----------------------------------------------
def pick(seq): return R.choice(seq)

HOSTELS = ["Green Valley Hostel","Sunrise Boys Hostel","Lotus Girls Hostel","Heritage PG","Campus View Residency","Oakwood Hostel","Blue Ridge Hostel","Royal Student Home","Maple Hostel","Sapphire Residency","Hillcrest Hostel","Orchid Hostel"]
COLLEGES = ["Greenfield Engineering College","Narayana Degree College","Vignan Institute","Sri Venkateswara College","Lakeview Business School","City Arts College","Bharathi Institute","Gandhi Medical College"]
FEEKIND = ["Hostel fee","Tuition fee","Exam fee","Bus fee","Library fee","Lab fee"]
HI_SCHOOLS = ["आदर्श विद्या मंदिर","ज्ञान भारती स्कूल","महात्मा गांधी विद्यालय","दीनदयाल पब्लिक स्कूल","राजकीय उच्च विद्यालय"]
TE_COLLEGES = ["వివేకానంద జూనియర్ కాలేజీ","నారాయణ డిగ్రీ కాలేజీ","విజ్ఞాన్ స్కూల్","రాజీవ్ గాంధీ కాలేజీ","కాకతీయ డిగ్రీ కాలేజీ"]

def fee_en():
    y, m, d = rdate(6, 11); amt = R.choice([8000, 12500, 15000, 22000, 32000, 38500, 45000, 52000, 18000, 9500]); amt += R.choice([0, 0, 500])
    if amt == 45000: amt += 500
    inst = pick(HOSTELS) if R.random() < .5 else pick(COLLEGES); kind = "Hostel fee" if inst in HOSTELS else pick(FEEKIND[1:])
    add("fee_receipt", "en", [(inst, "b"), kind + " receipt", f"Student: {pick(EN_NAMES)}", f"Amount paid: Rs {grp(amt)}", f"Paid on: {date('en', y, m, d)}", f"Receipt No: {R.choice('ABCDEFGH')}{R.randint(10000, 99999)}"],
        {"AMOUNT": str(amt), "DATE": iso(y, m, d)})

def fee_hi():
    y, m, d = rdate(6, 11); amt = R.choice([6000, 9800, 12000, 14500, 16000, 21000]); 
    add("fee_receipt", "hi", [(pick(HI_SCHOOLS), "b"), "शुल्क रसीद", f"छात्र: {pick(HI_NAMES)}", f"जमा राशि: ₹{grp(amt)}", f"दिनांक: {date('hi', y, m, d)}"], {"AMOUNT": str(amt), "DATE": iso(y, m, d)})

def fee_te():
    y, m, d = rdate(6, 11); amt = R.choice([6500, 9000, 15500, 19000, 24000, 31000])
    add("fee_receipt", "te", [(pick(TE_COLLEGES), "b"), "ఫీజు రసీదు", f"విద్యార్థి: {pick(TE_NAMES)}", f"చెల్లించిన మొత్తం: రూ. {grp(amt)}", f"తేదీ: {date('te', y, m, d)}"], {"AMOUNT": str(amt), "DATE": iso(y, m, d)})

PROVIDERS = {"Electricity": ["Eastern Power Corp","City Electricity Board","Bright Power Ltd"], "Gas": ["Indane Gas Booking","City Gas Ltd"], "Broadband": ["FiberNet Broadband","ConnectOne Broadband"], "Mobile": ["ConnectOne Mobile","Quick Mobile Postpaid"]}
def bill_en():
    while True:
        kind = R.choice(["Electricity", "Electricity", "Gas", "Broadband", "Mobile"]); y, m, d = rdate(8, 12)
        if kind == "Electricity" and (y, m, d) == (2026, 10, 15): continue
        break
    amt = R.randint(180, 3200)
    add("utility_bill", "en", [(pick(PROVIDERS[kind]), "b"), f"{kind} Bill" if kind != "Gas" else "Gas Cylinder Bill", f"Consumer No: {R.randint(1000000, 9999999)}", f"Amount due: Rs {grp(amt)}", f"Due date: {date('en', y, m, d)}"],
        {"AMOUNT": str(amt), "DATE": iso(y, m, d)})

def bill_hi():
    y, m, d = rdate(8, 12); amt = R.randint(300, 2800)
    add("utility_bill", "hi", [("उत्तर विद्युत वितरण निगम", "b"), "बिजली का बिल", f"उपभोक्ता संख्या: {R.randint(1000000, 9999999)}", f"देय राशि: ₹{grp(amt)}", f"अंतिम तिथि: {date('hi', y, m, d)}"], {"AMOUNT": str(amt), "DATE": iso(y, m, d)})

def bill_te():
    y, m, d = rdate(8, 12); amt = R.randint(300, 2800)
    add("utility_bill", "te", [("తెలంగాణ విద్యుత్ సంస్థ", "b"), "కరెంటు బిల్లు", f"వినియోగదారు సంఖ్య: {R.randint(1000000, 9999999)}", f"చెల్లించవలసిన మొత్తం: రూ. {grp(amt)}", f"చివరి తేదీ: {date('te', y, m, d)}"], {"AMOUNT": str(amt), "DATE": iso(y, m, d)})

CITIES = [("Hyderabad", "HYD"), ("Bengaluru", "BLR"), ("Chennai", "MAA"), ("Mumbai", "BOM"), ("Delhi", "DEL"), ("Kolkata", "CCU"), ("Pune", "PNQ"), ("Kochi", "COK"), ("Goa", "GOI"), ("Ahmedabad", "AMD")]
def ticket_en():
    kind = R.choice(["flight", "flight", "flight", "train", "bus"]); y, m, d = rdate(9, 12)
    a, b = R.sample(CITIES, 2)
    if kind == "flight":
        add("ticket", "en", [(f"{pick(['IndiGo 6E','Air India AI','Vistara UK','SpiceJet SG'])} {R.randint(100, 9999)}", "b"), "E-ticket", f"{a[0]} ({a[1]}) to {b[0]} ({b[1]})", f"Date: {date('en', y, m, d)}", f"Departure {R.randint(5, 22):02d}:{R.choice(['00','15','30','45'])}", f"PNR: {''.join(R.choice('ABCDEFGHJKLMNPQRSTUVWXYZ23456789') for _ in range(6))}"], {"DATE": iso(y, m, d)})
    elif kind == "train":
        add("ticket", "en", [(f"Express {R.randint(10000, 22999)}", "b"), "Train e-ticket", f"{a[0]} to {b[0]}", f"Journey date: {date('en', y, m, d)}", f"Coach S{R.randint(1, 9)}  Berth {R.randint(1, 72)}", f"PNR: {R.randint(1000000000, 9999999999)}"], {"DATE": iso(y, m, d)})
    else:
        add("ticket", "en", [(pick(["Orange Travels", "Kaveri Travels", "Green Line Bus"]), "b"), "Bus ticket", f"{a[0]} to {b[0]}", f"Date: {date('en', y, m, d)}", f"Seat {R.randint(1, 40)}{R.choice('AB')}", f"Booking id: {R.randint(100000, 999999)}"], {"DATE": iso(y, m, d)})

CLINICS = [("City Care Clinic", "Dr. R. Sharma", "General Medicine"), ("LifeLine Hospital", "Dr. S. Iyer", "Orthopaedics"), ("Sunshine Pediatrics", "Dr. K. Menon", "Pediatrics"), ("Dental Plus", "Dr. A. Khan", "Dentistry"),
           ("Vision Eye Care", "Dr. P. Reddy", "Ophthalmology"), ("Wellness Clinic", "Dr. N. Gupta", "Dermatology"), ("Care Women's Clinic", "Dr. L. Rao", "Gynaecology"), ("Heart & Health Centre", "Dr. V. Nair", "Cardiology")]
AREAS = [("Banjara Hills", "Hyderabad", 500034), ("Madhapur", "Hyderabad", 500081), ("Indiranagar", "Bengaluru", 560038), ("T Nagar", "Chennai", 600017), ("Kothrud", "Pune", 411038)]
def appt_en():
    c = pick(CLINICS); a = pick(AREAS); y, m, d = rdate(9, 11); p, pv = phone()
    add("appointment", "en", [(c[0], "b"), "Appointment card", f"{c[1]}, {c[2]}", f"Address: Plot {R.randint(1, 90)}, {a[0]}", f"{a[1]} {a[2]}", f"Phone: {p}", f"Appointment: {date('en', y, m, d)}, {R.randint(9, 17)}:{R.choice(['00','30'])}"],
        {"PHONE": pv, "DATE": iso(y, m, d), "ADDRESS": f"Plot x, {a[0]}, {a[1]} {a[2]}"})

def appt_hi():
    y, m, d = rdate(9, 11); p, pv = phone()
    add("appointment", "hi", [("जीवन ज्योति क्लिनिक", "b"), "अपॉइंटमेंट कार्ड", f"डॉ. {pick(['अनिल कपूर','सुनीता वर्मा','राजेश मिश्रा'])}", f"पता: प्लॉट {R.randint(1, 90)}, गांधी नगर", f"फोन: {p}", f"तारीख: {date('hi', y, m, d)}"], {"PHONE": pv, "DATE": iso(y, m, d)})

def appt_te():
    y, m, d = rdate(9, 11); p, pv = phone()
    add("appointment", "te", [("శ్రీ సాయి క్లినిక్", "b"), "అపాయింట్‌మెంట్ కార్డ్", f"డాక్టర్ {pick(['రమేష్','సునీత','కృష్ణ'])}", f"ఫోన్: {p}", f"తేదీ: {date('te', y, m, d)}"], {"PHONE": pv, "DATE": iso(y, m, d)})

SUBJECTS_EN = [
    ("Inorganic Chemistry - Lecture 4", ["Periodic trends in atomic radius", "Ionisation energy and electron affinity", "Crystal field theory basics", "Revision: coordination compounds"]),
    ("Physics - Thermodynamics", ["First and second law of thermodynamics", "Entropy and heat engines", "Carnot cycle efficiency", "Numericals on ideal gases"]),
    ("Data Structures - Trees", ["Binary search tree insertion and deletion", "AVL rotations", "Graph traversal BFS and DFS", "Heap and priority queues"]),
    ("Microeconomics - Demand and Supply", ["Law of demand and elasticity", "Market equilibrium", "Consumer surplus", "Price ceilings and floors"]),
    ("Linear Algebra - Week 3", ["Eigenvalues and eigenvectors", "Diagonalisation", "Orthogonal projections", "Singular value decomposition"]),
    ("Machine Learning - Notes", ["Gradient descent and learning rate", "Overfitting and regularisation", "Bias variance tradeoff", "Cross validation"]),
    ("Cell Biology - Chapter 5", ["Mitosis and meiosis stages", "Cell cycle checkpoints", "Chromosome structure", "Revision questions"]),
    ("Constitutional Law", ["Fundamental rights overview", "Directive principles", "Amendment procedure", "Landmark cases to remember"]),
    ("World History - Revolutions", ["Causes of the French revolution", "Reign of terror", "Napoleon and the empire", "Industrial revolution in Britain"]),
    ("Calculus - Integration", ["Integration by parts", "Substitution method", "Definite integrals and area", "Improper integrals"]),
    ("Geography - Monsoon", ["Southwest monsoon onset", "El Nino and rainfall", "Western ghats rainfall", "Cyclones in the Bay of Bengal"]),
    ("Operating systems lab", ["Process scheduling algorithms", "Deadlock detection", "Page replacement", "Semaphores and mutex"]),
]
SUBJECTS_EN = [s for s in SUBJECTS_EN if "Operating systems" not in s[0]]  # 'Operating Systems' is reserved for the timetable anchor
def notes_en():
    t, ls = pick(SUBJECTS_EN); add("notes", "en", [(t, "b")] + R.sample(ls, 3))
def notes_hi():
    t, ls = pick([("भौतिकी - न्यूटन के नियम", ["गति का पहला नियम", "बल और त्वरण", "क्रिया और प्रतिक्रिया"]), ("गणित - त्रिकोणमिति", ["साइन कोसाइन के सूत्र", "ऊँचाई और दूरी के प्रश्न", "त्रिकोणमितीय सर्वसमिकाएँ"]), ("भूगोल - भारत की नदियाँ", ["गंगा नदी तंत्र", "प्रायद्वीपीय नदियाँ", "बाढ़ और सिंचाई"])])
    add("notes", "hi", [(t, "b")] + ls)
def notes_te():
    t, ls = pick([("జీవశాస్త్రం - కణ విభజన", ["మైటోసిస్ దశలు", "మియోసిస్ ప్రాముఖ్యత", "క్రోమోజోములు"]), ("గణితం - బీజగణితం", ["వర్గ సమీకరణాలు", "అంకశ్రేణులు", "సమీకరణాల సాధన"])])
    add("notes", "te", [(t, "b")] + ls)

TIMETABLES = [("B.Tech II Year", ["Data Structures", "Digital Logic", "Discrete Mathematics", "Probability and Statistics"]), ("MBA I Semester", ["Marketing Management", "Financial Accounting", "Organisational Behaviour", "Business Statistics"]),
              ("B.Sc III Year", ["Quantum Mechanics", "Organic Synthesis", "Real Analysis", "Genetics"])]
def timetable_en():
    t, subs = pick(TIMETABLES); y, m, _ = rdate(10, 11)
    days = sorted(R.sample(range(1, 28), 4)); lines = [f"{d} {MONTH_EN[m-1]} {y}  {s}" for d, s in zip(days, subs)]
    add("timetable", "en", [("Semester Exam Timetable", "b"), t] + lines, {"DATE": iso(y, m, days[0])})
def timetable_hi():
    y, m, d = rdate(10, 11)
    add("timetable", "hi", [("परीक्षा समय सारणी", "b"), "कक्षा 10", f"{d} {MONTH_HI[m-1]} {y}  गणित", f"{d+2} {MONTH_HI[m-1]} {y}  विज्ञान", f"{d+4} {MONTH_HI[m-1]} {y}  सामाजिक विज्ञान"], {"DATE": iso(y, m, d)})

def rent():
    y, m, d = rdate(6, 11); amt = R.choice([8000, 9500, 12000, 15000, 18000])
    add("rent_receipt", "en", [("Rent Receipt", "b"), f"Tenant: {pick(EN_NAMES)}", f"Landlord: {pick(EN_NAMES)}", f"Month: {MONTH_EN[m-1]} {y}", f"Rent received: Rs {grp(amt)}", f"Date: {date('en', y, m, d)}"], {"AMOUNT": str(amt), "DATE": iso(y, m, d)})
def insurance():
    y, m, d = rdate(9, 12); amt = R.choice([8600, 12400, 15800, 21000])
    add("insurance", "en", [(pick(["Star Health Insurance", "LifeSecure Insurance", "Secure Shield Health"]), "b"), "Premium reminder", f"Policy no: {R.randint(10000000, 99999999)}", f"Premium due: Rs {grp(amt)}", f"Due date: {date('en', y, m, d)}"], {"AMOUNT": str(amt), "DATE": iso(y, m, d)})
def certificate():
    add("certificate", "en", [("Certificate of Completion", "b"), f"This is to certify that {pick(EN_NAMES)}", f"has completed the course {pick(['Python Programming', 'Digital Marketing', 'Data Analysis with Excel', 'Public Speaking'])}", f"Issued on {date('en', *rdate(5, 10))}"])
def shopping():
    items = R.sample(["rice 5 kg", "toor dal 2 kg", "milk 2 litres", "eggs 12", "tomatoes 1 kg", "onions 2 kg", "cooking oil 1 litre", "bread", "tea powder", "sugar 1 kg", "detergent", "soap 4"], 6)
    add("shopping_list", "en", [("Shopping list", "b")] + items)
def recipe():
    d, ing = pick([("Pesarattu", ["green gram 1 cup", "rice 2 spoons", "ginger and green chilli", "soak 4 hours and grind"]), ("Vegetable biryani", ["basmati rice 2 cups", "mixed vegetables", "biryani masala", "cook on low flame 20 minutes"]), ("Lemon rice", ["cooked rice 2 cups", "lemon juice", "curry leaves and mustard", "peanuts and turmeric"]), ("Masala chai", ["milk and water", "tea leaves", "ginger and cardamom", "boil 5 minutes"])])
    add("recipe", "en", [(f"Recipe: {d}", "b")] + ing)
def chat():
    msgs = R.sample(["Mom: Did you reach the hostel?", "You: Yes, reached an hour ago", "Mom: Eat dinner on time", "Friend: Are we meeting for the project tomorrow?", "You: Yes, 4 pm in the library", "Friend: Bring the lab record", "Mom: Call me when you are free", "You: Will do, love you"], 5)
    add("chat", "en", [("Messages", "b")] + msgs)

# ---------------------------------------------------------------- UPI payments (ledger truth) --------------------------
PAYEES = ["Chai Point","Ravi Tea Stall","Sunrise Pharmacy","Hotel Annapurna","Swathi Stationery","Anil Kumar","Priya Sharma","Metro Mart","City Bookshop","Campus Canteen","Auto Rajesh","Green Grocers","Fresh Juice Centre","Print And Copy","Mani Tiffin Centre","Kamala Sweets"]
def money_txt(paise, style):
    whole, frac = divmod(paise, 100); body = grp(whole) + (f".{frac:02d}" if frac else "")
    return {0: f"₹{body}", 1: f"₹ {body}", 2: f"Rs. {body}"}[style % 3]

def upi_lines(layout, payee, paise, y, m, d, hh, mm, ref, style, outcome="success", received=False):
    ap = "AM" if hh < 12 else "PM"; h12 = hh % 12 or 12
    dt_a = f"{d} {MONTH_EN[m-1]} {y}, {h12}:{mm:02d} {ap}"; dt_b = f"{MONTH_EN[m-1]} {d}, {y} at {h12:02d}:{mm:02d} {ap}"
    amt = money_txt(paise, style)
    status = {"failed": "Payment failed", "pending": "Payment pending"}.get(outcome) or ("Money received" if received else None)
    if layout == 0:
        return [("Google Pay", "b"), (amt, "big"), status or "Completed", f"{'From' if received else 'To'} {payee}", dt_a, "UPI transaction ID", ref]
    if layout == 1:
        return [("PhonePe", "b"), (status or "Payment Successful", "b"), (amt, "big"), f"{'Received from' if received else 'Paid to'} {payee}", dt_b, f"UTR: {ref}", "Debited from XXXX1234"]
    return [("Paytm UPI", "b"), (status or "Paid Successfully", "b"), "Received from" if received else "Paid to", (payee, "b"), (amt, "big"), dt_a, f"UPI Ref No: {ref}"]

used_refs = set()
def new_ref():
    while True:
        r = str(R.randint(600000000000, 699999999999))
        if r not in used_refs: used_refs.add(r); return r

def add_upi(payee, paise, y, m, d, hh, mm, layout, style, outcome="success", received=False, ref=None, anchor=None, dup_of=None):
    ref = ref or new_ref()
    add("upi_payment", "en", upi_lines(layout, payee, paise, y, m, d, hh, mm, ref, style, outcome, received),
        {"AMOUNT": str(paise // 100) if paise % 100 == 0 else f"{paise // 100}.{paise % 100:02d}".rstrip("0")} if outcome == "success" and not received else {},
        anchor=anchor, upi={"outcome": outcome.upper(), "direction": "RECEIVED" if received else "PAID", "paise": paise, "payee": payee, "date": iso(y, m, d), "time": f"{hh:02d}:{mm:02d}", "ref": ref, "dup_of": dup_of})

def upi_batch():
    # A7: the anchor payment
    add_upi("Lakshmi Tiffins", 24000, 2026, 9, 14, 8, 20, 1, 0, anchor="A7")
    for i in range(34):  # ordinary successful payments across Aug-Oct 2026
        add_upi(pick(PAYEES), R.randint(20, 3200) * 100 + R.choice([0, 0, 0, 50]), 2026, R.randint(8, 10), R.randint(1, 28), R.randint(7, 22), R.randint(0, 59), R.randint(0, 2), R.randint(0, 2))
    firsts = [d for d in docs if d["type"] == "upi_payment" and d["upi"]["outcome"] == "SUCCESS" and d["anchor"] is None][:3]
    for f in firsts:  # the same payment screenshotted again in another layout: the ledger must count it once
        u = f["upi"]; y, m, d = map(int, u["date"].split("-")); hh, mm = map(int, u["time"].split(":"))
        add_upi(u["payee"], u["paise"], y, m, d, hh, mm, (R.randint(0, 2) + 1) % 3, R.randint(0, 2), ref=u["ref"], dup_of=f["id"])
    for outcome, rec in [("failed", False)] * 3 + [("pending", False)] * 2 + [(" success", True)] * 3:
        add_upi(pick(PAYEES), R.randint(50, 2500) * 100, 2026, R.randint(8, 10), R.randint(1, 28), R.randint(8, 21), R.randint(0, 59), R.randint(0, 2), R.randint(0, 2), outcome=outcome.strip(), received=rec)

# ---------------------------------------------------------------- assemble 300 -----------------------------------------------
upi_batch()
plan = [(fee_en, 24), (fee_hi, 11), (fee_te, 11), (bill_en, 22), (bill_hi, 9), (bill_te, 7), (ticket_en, 44), (appt_en, 17), (appt_hi, 4), (appt_te, 4), (notes_en, 27), (notes_hi, 3),
        (notes_te, 2), (timetable_en, 10), (timetable_hi, 3), (rent, 8), (insurance, 8), (certificate, 6), (shopping, 7), (recipe, 4), (chat, 13)]
def _text(d): return "\n".join(l if isinstance(l, str) else l[0] for l in d["lines"])
seen_text = {_text(d) for d in docs}
for fn, n in plan:
    made = 0
    while made < n:  # regenerate a filler whose text duplicates an earlier document (the indexer would drop it as a duplicate)
        fn()
        if _text(docs[-1]) in seen_text: docs.pop(); continue
        seen_text.add(_text(docs[-1])); made += 1
assert len(docs) == 300, len(docs)

# ---------------------------------------------------------------- uniqueness guards ------------------------------------------
def text(d): return "\n".join(l if isinstance(l, str) else l[0] for l in d["lines"])
anchor_keys = {"A1": ["Sai Vidya"], "A2": ["Southern Power"], "A3": ["Visakhapatnam", "VTZ"], "A4": ["Water Bill", "Metro Water"], "A5": ["SN1", "Organic Chemistry"], "A6": ["Meera Rao"],
               "A7": ["Lakshmi Tiffins"], "A8": ["Operating Systems"], "A9": ["सरस्वती"], "A10": ["मुगल"], "A11": ["శ్రీ చైతన్య"]}
for a, keys in anchor_keys.items():
    owners = [d["id"] for d in docs if any(k in text(d) for k in keys)]
    assert len(owners) == 1 and docs[int(owners[0][1:]) - 1]["anchor"] == a, (a, owners)
assert not [d for d in docs if d["type"] == "utility_bill" and d["lang"] == "en" and "Electricity" in text(d) and "15 Oct 2026" in text(d) and d["anchor"] != "A2"]
assert len({text(d) for d in docs}) == 300, "two documents have identical text (they would be deduplicated by the indexer)"
assert not [d for d in docs if "car" in text(d).lower().split() and "insurance" in text(d).lower()], "negative query 'car insurance' must have no car-insurance document"

# ---------------------------------------------------------------- ledger truth --------------------------------------------------
ledger = collections.defaultdict(int); upi_docs = [d for d in docs if d["type"] == "upi_payment"]
seen = set()
for d in upi_docs:
    u = d["upi"]
    if u["outcome"] == "SUCCESS" and u["direction"] == "PAID" and u["ref"] not in seen:
        seen.add(u["ref"]); ledger[u["date"][:7]] += u["paise"]

by_anchor = {d["anchor"]: d["id"] for d in docs if d["anchor"]}
json.dump({"seed": 2026, "docs": docs, "ledger_truth_paise": dict(sorted(ledger.items())), "n_docs": len(docs)}, open(OUT / "manifest.json", "w"), ensure_ascii=False, indent=1)

# ---------------------------------------------------------------- queries -----------------------------------------------------------
# 11 intents x 5 styles. style: en, te (Telugu script), hi (Devanagari), rt (Roman-script Telugu), mix (code-mixed).
# NOTE: the Telugu, Hindi, Roman-Telugu and mixed queries were written by the author and have NOT had a native-speaker review.
Q = [
 ("A1", "value", {"type": "AMOUNT", "value": "45000"}, {"en": "how much was the Sai Vidya hostel fee", "te": "సాయి విద్య హాస్టల్ ఫీజు ఎంత", "hi": "साई विद्या हॉस्टल की फीस कितनी थी", "rt": "sai vidya hostel fee entha", "mix": "Sai Vidya hostel fee ఎంత కట్టాను"}),
 ("A2", "find", None, {"en": "electricity bill due 15 October", "te": "అక్టోబర్ 15 కరెంటు బిల్లు చివరి తేదీ", "hi": "15 अक्टूबर को बिजली का बिल जमा करने की तारीख", "rt": "october 15 current bill last date", "mix": "electricity bill అక్టోబర్ 15 due"}),
 ("A3", "find", None, {"en": "flight to Visakhapatnam", "te": "విశాఖపట్నం విమానం టికెట్", "hi": "विशाखापत्तनम की फ्लाइट का टिकट", "rt": "vizag flight ticket", "mix": "Visakhapatnam కి flight ticket"}),
 ("A4", "value", {"type": "DATE", "value": "2026-11-03"}, {"en": "when is the water bill due", "te": "నీటి బిల్లు ఎప్పుడు కట్టాలి", "hi": "पानी का बिल कब भरना है", "rt": "water bill eppudu kattali", "mix": "water bill ఎప్పుడు due"}),
 ("A5", "find", None, {"en": "organic chemistry notes on SN1 and SN2 reactions", "te": "సేంద్రీయ రసాయన శాస్త్రం SN1 SN2 నోట్స్", "hi": "कार्बनिक रसायन SN1 और SN2 अभिक्रिया के नोट्स", "rt": "organic chemistry SN1 SN2 notes", "mix": "organic chemistry notes లో SN1 SN2"}),
 ("A6", "value", {"type": "PHONE", "value": "+919848012345"}, {"en": "Dr Meera Rao clinic phone number", "te": "డాక్టర్ మీరా రావు క్లినిక్ ఫోన్ నంబర్", "hi": "डॉ. मीरा राव के क्लिनिक का फोन नंबर", "rt": "Dr Meera Rao clinic phone number enti", "mix": "Dr Meera Rao clinic ఫోన్ number"}),
 ("A7", "find", None, {"en": "payment to Lakshmi Tiffins", "te": "లక్ష్మి టిఫిన్స్ కి చెల్లింపు", "hi": "लक्ष्मी टिफिन्स को भुगतान", "rt": "Lakshmi Tiffins ki payment chesanu", "mix": "Lakshmi Tiffins కి payment screenshot"}),
 ("A8", "find", None, {"en": "exam timetable December operating systems", "te": "డిసెంబర్ పరీక్షల టైమ్ టేబుల్ ఆపరేటింగ్ సిస్టమ్స్", "hi": "दिसंबर परीक्षा समय सारणी ऑपरेटिंग सिस्टम", "rt": "december exam timetable operating systems", "mix": "exam timetable డిసెంబర్ operating systems"}),
 ("A9", "value", {"type": "AMOUNT", "value": "12500"}, {"en": "how much was the Saraswati Vidya Mandir school fee", "te": "సరస్వతి విద్యా మందిర్ స్కూల్ ఫీజు ఎంత", "hi": "सरस्वती विद्या मंदिर की स्कूल फीस कितनी जमा की", "rt": "saraswati vidya mandir school fee entha", "mix": "Saraswati Vidya Mandir school fee कितनी थी"}),
 ("A10", "find", None, {"en": "history notes on the Mughal empire and Akbar", "te": "మొఘల్ సామ్రాజ్యం అక్బర్ చరిత్ర నోట్స్", "hi": "मुगल साम्राज्य और अकबर की नीतियों पर इतिहास के नोट्स", "rt": "mughal samrajyam akbar history notes", "mix": "Mughal empire Akbar గురించి history notes"}),
 ("A11", "value", {"type": "AMOUNT", "value": "28000"}, {"en": "how much was the Sri Chaitanya junior college fee", "te": "శ్రీ చైతన్య జూనియర్ కాలేజీ ఫీజు ఎంత", "hi": "श्री चैतन्य जूनियर कॉलेज की फीस कितनी थी", "rt": "sri chaitanya junior college fee entha", "mix": "Sri Chaitanya junior college fee ఎంత"}),
 (None, "negative", None, {"en": "how much was the car insurance", "te": "కారు ఇన్సూరెన్స్ ఎంత", "hi": "कार बीमा कितना था", "rt": "car insurance entha", "mix": "car insurance ఎంత కట్టాను"}),
]
anchor_ids = {a: d["id"] for d in docs if (a := d["anchor"])}
queries = []
for intent, kind, answer, texts in Q:
    for style, t in texts.items():
        queries.append({"id": f"q{len(queries)+1:02d}", "intent": intent or "NEG", "style": style, "kind": kind, "text": t,
                        "relevant": [anchor_ids[intent]] if intent else [], "answer": answer,
                        "target_lang": next(d["lang"] for d in docs if d["id"] == anchor_ids[intent]) if intent else None})
assert len(queries) == 60
json.dump({"queries": queries, "styles": ["en", "te", "hi", "rt", "mix"]}, open(OUT / "queries.json", "w"), ensure_ascii=False, indent=1)
c = collections.Counter((d["type"], d["lang"]) for d in docs)
print(f"{len(docs)} documents, {len(queries)} queries")
for k, v in sorted(c.items()): print(f"  {k[0]:14s} {k[1]}  {v}")
print("ledger truth (paise):", dict(ledger), " upi docs:", len(upi_docs))
