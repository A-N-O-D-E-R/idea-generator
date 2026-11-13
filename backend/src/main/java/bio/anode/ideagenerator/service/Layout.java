package bio.anode.ideagenerator.service;

import bio.anode.ideagenerator.model.Idea;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/** Where new ideas land on the board canvas. */
@UtilityClass
class Layout {
    /** Arc of n children pointing away from where the parent came from. */
    List<double[]> arcPositions(Idea parent, Idea grandparent, int n) {
        double dir = grandparent != null ? Math.atan2(parent.y() - grandparent.y(), parent.x() - grandparent.x()) : Math.random() * Math.PI * 2;
        double spread = grandparent != null ? Math.PI * 2 / 3 : Math.PI * 2;
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = grandparent != null ? dir - spread / 2 + spread * (i + 0.5) / n : dir + spread * i / n;
            out.add(new double[]{parent.x() + Math.cos(a) * 190, parent.y() + Math.sin(a) * 190});
        }
        return out;
    }

    /** Where a newcomer (guest, lead) lands: just right of what is already on the board. */
    double[] besideBoard(List<Idea> ideas) {
        if (ideas.isEmpty()) return new double[]{0, 0};
        double maxX = ideas.stream().mapToDouble(Idea::x).max().getAsDouble();
        double meanY = ideas.stream().mapToDouble(Idea::y).sum() / ideas.size();
        return new double[]{maxX + 240, meanY + (Math.random() - 0.5) * 160};
    }
}
