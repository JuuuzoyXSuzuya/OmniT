package mod.omnit;

/** Таблица регионов: ISO-код, название, MCC/MNC и имя оператора. */
final class RegionData {

    static final class Entry {
        final String iso;      // "US"
        final String name;     // "United States"
        final String mcc;      // "310"
        final String mnc;      // "260"
        final String carrier;  // "T-Mobile"

        Entry(String iso, String name, String mcc, String mnc, String carrier) {
            this.iso = iso;
            this.name = name;
            this.mcc = mcc;
            this.mnc = mnc;
            this.carrier = carrier;
        }
    }

    static final Entry[] ALL = new Entry[] {
        new Entry("US", "United States", "310", "260", "T-Mobile"),
        new Entry("CA", "Canada", "302", "720", "Rogers"),
        new Entry("MX", "Mexico", "334", "020", "Telcel"),
        new Entry("BR", "Brazil", "724", "05", "Claro BR"),
        new Entry("GB", "United Kingdom", "234", "15", "Vodafone UK"),
        new Entry("DE", "Germany", "262", "01", "Telekom.de"),
        new Entry("FR", "France", "208", "01", "Orange F"),
        new Entry("IT", "Italy", "222", "01", "TIM"),
        new Entry("ES", "Spain", "214", "07", "Movistar"),
        new Entry("NL", "Netherlands", "204", "08", "KPN"),
        new Entry("PL", "Poland", "260", "02", "T-Mobile.pl"),
        new Entry("SE", "Sweden", "240", "01", "Telia"),
        new Entry("NO", "Norway", "242", "01", "Telenor"),
        new Entry("UA", "Ukraine", "255", "01", "Vodafone UA"),
        new Entry("KZ", "Kazakhstan", "401", "01", "Beeline KZ"),
        new Entry("TR", "Turkey", "286", "01", "Turkcell"),
        new Entry("IL", "Israel", "425", "01", "Partner"),
        new Entry("SA", "Saudi Arabia", "420", "01", "STC"),
        new Entry("AE", "United Arab Emirates", "424", "02", "Etisalat"),
        new Entry("EG", "Egypt", "602", "01", "Orange EG"),
        new Entry("JP", "Japan", "440", "10", "NTT DOCOMO"),
        new Entry("KR", "South Korea", "450", "05", "SKTelecom"),
        new Entry("ID", "Indonesia", "510", "10", "Telkomsel"),
        new Entry("TH", "Thailand", "520", "01", "AIS"),
        new Entry("VN", "Vietnam", "452", "04", "Viettel"),
        new Entry("PH", "Philippines", "515", "02", "Globe"),
        new Entry("MY", "Malaysia", "502", "12", "Maxis"),
        new Entry("SG", "Singapore", "525", "01", "Singtel"),
        new Entry("AU", "Australia", "505", "01", "Telstra"),
    };

    static Entry find(String iso) {
        if (iso == null) return null;
        String up = iso.trim().toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].iso.equals(up)) return ALL[i];
        }
        return null;
    }

    private RegionData() {}
}
