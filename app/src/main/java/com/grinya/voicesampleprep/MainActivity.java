package com.grinya.voicesampleprep;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.grinya.voicesampleprep.model.Bank;
import com.grinya.voicesampleprep.model.BankManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

  private BankManager bankManager;
  private RecyclerView rv;
  private TextView txtEmpty;
  private BankAdapter adapter;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    bankManager = new BankManager(this);
    rv = findViewById(R.id.rvBanks);
    txtEmpty = findViewById(R.id.txtEmpty);
    adapter = new BankAdapter();
    rv.setLayoutManager(new LinearLayoutManager(this));
    rv.setAdapter(adapter);

    findViewById(R.id.fabAdd).setOnClickListener(v -> showCreateBankDialog());

    findViewById(R.id.btnSettings).setOnClickListener(v -> {
      startActivity(new Intent(this, SettingsActivity.class));
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    reload();
  }

  /** E10: DiffUtil вместо notifyDataSetChanged. */
  private void reload() {
    List<Bank> banks = bankManager.listBanks();
    adapter.submit(banks);
    txtEmpty.setVisibility(banks.isEmpty() ? View.VISIBLE : View.GONE);
  }

  private void showCreateBankDialog() {
    EditText input = new EditText(this);
    input.setHint("Имя банка");
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    new AlertDialog.Builder(this).setTitle("Новый банк").setView(input)
        .setPositiveButton("Создать", (d, w) -> {
          String name = input.getText().toString().trim();
          if (name.isEmpty())
            name = "bank";
          Bank b = bankManager.createBank(name);
          reload();
          openBank(b);
        }).setNegativeButton("Отмена", null).show();
  }

  /** E11: передаём имя банка через Intent extra, статику не используем. */
  private void openBank(Bank b) {
    Intent i = new Intent(this, BankActivity.class);
    i.putExtra("bankName", b.name);
    startActivity(i);
  }

  // ---------- adapter ----------

  private class BankAdapter extends RecyclerView.Adapter<BankAdapter.VH> {
    private List<Bank> data = new ArrayList<>();

    void submit(List<Bank> newData) {
      DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
        @Override
        public int getOldListSize() {
          return data.size();
        }

        @Override
        public int getNewListSize() {
          return newData.size();
        }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
          return Objects.equals(data.get(oldPos).name, newData.get(newPos).name);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
          Bank a = data.get(oldPos);
          Bank b = newData.get(newPos);
          return Objects.equals(a.name, b.name) && a.clips.size() == b.clips.size();
        }
      });
      data = newData;
      diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bank, parent, false);
      return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
      Bank b = data.get(pos);
      h.name.setText(b.name);
      h.info.setText(b.clips.size() + " клипов");
      h.itemView.setOnClickListener(v -> openBank(b));
      h.itemView.setOnLongClickListener(v -> {
        new AlertDialog.Builder(MainActivity.this).setTitle("Удалить банк?").setMessage(b.name)
            .setPositiveButton("Удалить", (d, w) -> {
              bankManager.deleteBank(b);
              reload();
            }).setNegativeButton("Отмена", null).show();
        return true;
      });
    }

    @Override
    public int getItemCount() {
      return data.size();
    }

    class VH extends RecyclerView.ViewHolder {
      TextView name, info;

      VH(View v) {
        super(v);
        name = v.findViewById(R.id.txtBankName);
        info = v.findViewById(R.id.txtBankInfo);
      }
    }
  }
}