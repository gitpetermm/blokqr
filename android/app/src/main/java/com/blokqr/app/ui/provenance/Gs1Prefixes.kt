package com.blokqr.app.ui.provenance
/** Nature d'un préfixe GS1. */
enum class Gs1Kind { MEMBER_ORG, RESTRICTED, ISBN, ISSN, COUPON, GS1_GLOBAL, UNKNOWN }
/**
 * Résultat d'un préfixe : [kind] = nature ; [iso] = code pays ISO 3166 du pays
 * d'ENREGISTREMENT (pour une organisation membre mono-pays), sinon null.
 * Le libellé final (nom de pays localisé, ou texte de catégorie) est construit
 * par l'UI à partir de ces champs -> support multilingue.
 */
data class Gs1Entry(val kind: Gs1Kind, val iso: String?)
/**
 * Table des préfixes GS1, EMBARQUÉE et 100 % HORS-LIGNE.
 *
 * AVERTISSEMENT ESSENTIEL : le préfixe identifie l'ORGANISATION MEMBRE GS1 qui a
 * émis le numéro (pays où l'entreprise s'est ENREGISTRÉE), et NON le pays de
 * fabrication du produit. Ne jamais présenter ceci comme un pays d'origine.
 * Instantané indicatif : GS1 met la liste à jour.
 */
object Gs1Prefixes {
    fun lookup(prefix: Int): Gs1Entry = when (prefix) {
        in 0..19 -> mo("US")
        in 20..29 -> restricted()
        in 30..39 -> mo("US")
        in 40..49 -> restricted()
        in 50..139 -> mo("US")
        in 200..299 -> restricted()
        in 300..379 -> mo("FR")
        380 -> mo("BG"); 383 -> mo("SI"); 385 -> mo("HR"); 387 -> mo("BA")
        389 -> mo("ME"); 390 -> mo("XK")
        in 400..440 -> mo("DE")
        in 450..459 -> mo("JP")
        in 460..469 -> mo("RU")
        470 -> mo("KG"); 471 -> mo("TW"); 474 -> mo("EE"); 475 -> mo("LV")
        476 -> mo("AZ"); 477 -> mo("LT"); 478 -> mo("UZ"); 479 -> mo("LK")
        480 -> mo("PH"); 481 -> mo("BY"); 482 -> mo("UA"); 483 -> mo("TM")
        484 -> mo("MD"); 485 -> mo("AM"); 486 -> mo("GE"); 487 -> mo("KZ")
        488 -> mo("TJ"); 489 -> mo("HK")
        in 490..499 -> mo("JP")
        in 500..509 -> mo("GB")
        in 520..521 -> mo("GR")
        528 -> mo("LB"); 529 -> mo("CY"); 530 -> mo("AL"); 531 -> mo("MK")
        535 -> mo("MT"); 539 -> mo("IE")
        in 540..549 -> mo("BE")
        560 -> mo("PT"); 569 -> mo("IS")
        in 570..579 -> mo("DK")
        590 -> mo("PL"); 594 -> mo("RO"); 599 -> mo("HU")
        in 600..601 -> mo("ZA")
        603 -> mo("GH"); 604 -> mo("SN"); 608 -> mo("BH"); 609 -> mo("MU")
        611 -> mo("MA"); 613 -> mo("DZ"); 615 -> mo("NG"); 616 -> mo("KE")
        617 -> mo("CM"); 618 -> mo("CI"); 619 -> mo("TN"); 620 -> mo("TZ")
        621 -> mo("SY"); 622 -> mo("EG"); 623 -> mo("BN"); 624 -> mo("LY")
        625 -> mo("JO"); 626 -> mo("IR"); 627 -> mo("KW"); 628 -> mo("SA")
        629 -> mo("AE"); 630 -> mo("QA"); 631 -> mo("NA")
        in 640..649 -> mo("FI")
        in 690..699 -> mo("CN")
        in 700..709 -> mo("NO")
        729 -> mo("IL")
        in 730..739 -> mo("SE")
        740 -> mo("GT"); 741 -> mo("SV"); 742 -> mo("HN"); 743 -> mo("NI")
        744 -> mo("CR"); 745 -> mo("PA"); 746 -> mo("DO"); 750 -> mo("MX")
        in 754..755 -> mo("CA")
        759 -> mo("VE")
        in 760..769 -> mo("CH")
        in 770..771 -> mo("CO")
        773 -> mo("UY"); 775 -> mo("PE"); 777 -> mo("BO")
        in 778..779 -> mo("AR")
        780 -> mo("CL"); 784 -> mo("PY"); 786 -> mo("EC")
        in 789..790 -> mo("BR")
        in 800..839 -> mo("IT")
        in 840..849 -> mo("ES")
        850 -> mo("CU"); 858 -> mo("SK"); 859 -> mo("CZ"); 860 -> mo("RS")
        865 -> mo("MN"); 867 -> mo("KP")
        in 868..869 -> mo("TR")
        in 870..879 -> mo("NL")
        880 -> mo("KR"); 883 -> mo("MM"); 884 -> mo("KH"); 885 -> mo("TH")
        888 -> mo("SG"); 890 -> mo("IN"); 893 -> mo("VN"); 894 -> mo("BD")
        896 -> mo("PK"); 899 -> mo("ID")
        in 900..919 -> mo("AT")
        in 930..939 -> mo("AU")
        in 940..949 -> mo("NZ")
        950 -> global(); 951 -> global()
        955 -> mo("MY"); 958 -> mo("MO")
        in 960..969 -> global()
        977 -> Gs1Entry(Gs1Kind.ISSN, null)
        in 978..979 -> Gs1Entry(Gs1Kind.ISBN, null)
        in 980..984 -> Gs1Entry(Gs1Kind.COUPON, null)
        in 990..999 -> Gs1Entry(Gs1Kind.COUPON, null)
        else -> Gs1Entry(Gs1Kind.UNKNOWN, null)
    }
    private fun mo(iso: String) = Gs1Entry(Gs1Kind.MEMBER_ORG, iso)
    private fun restricted() = Gs1Entry(Gs1Kind.RESTRICTED, null)
    private fun global() = Gs1Entry(Gs1Kind.GS1_GLOBAL, null)
}
